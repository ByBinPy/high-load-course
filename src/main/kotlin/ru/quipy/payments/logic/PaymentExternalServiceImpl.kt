package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    @field:Qualifier("parallelLimiter")
    private val parallelLimiter: Semaphore,
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val slidingWindowRateLimiter = SlidingWindowRateLimiter(
        rate = rateLimitPerSec.toLong(),
        window = Duration.ofMillis(1_000)
    )

    private val client = OkHttpClient.Builder().callTimeout(Duration.ofMillis(1100)).build()

    override fun getAccountProperties(): PaymentAccountProperties {
        return properties
    }

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        try {
            val parallelLimiterTimeout = calculateRemainingTime(deadline, requestAverageProcessingTime.toMillis())
            if (parallelLimiterTimeout <= 0 ||
                !parallelLimiter.tryAcquire(parallelLimiterTimeout, TimeUnit.MILLISECONDS)) {

                logger.warn("[$accountName] Parallel limiter timeout for payment $paymentId")
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId)
                }
                return
            }

            val rateLimiterTimeout = calculateRemainingTime(deadline, requestAverageProcessingTime.toMillis())
            if (rateLimiterTimeout <= 0 || !slidingWindowRateLimiter.tickBlocking(Duration.ofMillis(rateLimiterTimeout))) {

                logger.warn("[$accountName] Rate limiter timeout for payment $paymentId")
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId)
                }
                return
            }

            val request = Request.Builder().run {
                url("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
                post(emptyBody)
                build()
            }

            val httpTimeout = calculateRemainingTime(deadline, requestAverageProcessingTime.toMillis())
            if (httpTimeout <= 0) {
                logger.warn("[$accountName] HTTP request timeout before execution for payment $paymentId")
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = "Request timeout before execution")
                }
                return
            }


            var isCompletedRequest = false
            var retryCount = 0
            var isOk: Boolean
            while (!isCompletedRequest && now() < deadline) {
                isOk = false
                if (calculateRemainingTime(deadline, requestAverageProcessingTime.toMillis()) < 0) {
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, "deadline was expired")
                    }
                    return
                }
            client.newCall(request).execute().use { response ->
                val body = try {
                    response.body?.string()?.let {
                        mapper.readValue(it, ExternalSysResponse::class.java)
                    } ?: ExternalSysResponse(
                        transactionId.toString(),
                        paymentId.toString(),
                        false,
                        "Empty response body"
                    )
                } catch (e: Exception) {
                    logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                    ExternalSysResponse(
                        transactionId.toString(),
                        paymentId.toString(),
                        false,
                        e.message ?: "Unknown error"
                    )
                }

                isOk = !body.result && !(response.code >= 500 || response.code == 429)
                isCompletedRequest = if (isOk) {
                    if (retryCount < 3) {
                        retryCount++
                        val backoffTime = (2.0.pow(retryCount.toDouble()) * 10 + Random().nextLong(0, 10)).toLong()
                        Thread.sleep(backoffTime)
                        continue
                    } else {
                        true
                    }
                } else {
                    true
                }
                logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                paymentESService.update(paymentId) {
                    it.logProcessing(body.result, now(), transactionId, reason = body.message)
                }
                }
            }
        } catch (e: Exception) {
            when (e) {
                is SocketTimeoutException -> {
                    logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = "HTTP request timeout")
                    }
                }
                else -> {
                    logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = e.message ?: "Unknown error")
                    }
                }
            }
        } finally {
            if (parallelLimiter.availablePermits() < parallelRequests) {
                parallelLimiter.release()
            }
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    private fun calculateRemainingTime(deadline: Long, requestAverageProcessingTime: Long): Long {
        return deadline - now() - (requestAverageProcessingTime * 0.01).toLong()
    }

}

public fun now() = System.currentTimeMillis()