package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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

    private val httpClient = HttpClient
        .newBuilder()
        .executor(Executors.newFixedThreadPool(parallelRequests))
        .version(HttpClient.Version.HTTP_2)
        .build()

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

        val parallelLimiterTimeout = calculateRemainingTime(deadline, requestAverageProcessingTime.toMillis())
        if (parallelLimiterTimeout <= 0 || !parallelLimiter.tryAcquire(
                parallelLimiterTimeout,
                TimeUnit.MILLISECONDS
            )
        ) {

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

//            val request = HttpRequest.newBuilder().run {
//                url("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
//                post(emptyBody)
//                build()
//            }

        // Kotlin
        val request = HttpRequest.newBuilder()
            .uri(URI("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"))
            .timeout(Duration.ofMillis(requestAverageProcessingTime.toMillis()))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, throwable ->
                try {
                    if (throwable != null) {
                        when (throwable.cause) {
                            is SocketTimeoutException -> {
                                logger.error(
                                    "[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId",
                                    throwable
                                )
                                paymentESService.update(paymentId) {
                                    it.logProcessing(false, now(), transactionId, reason = "HTTP request timeout")
                                }
                            }

                            else -> {
                                logger.error(
                                    "[$accountName] Payment failed for txId: $transactionId, payment: $paymentId",
                                    throwable
                                )
                                paymentESService.update(paymentId) {
                                    it.logProcessing(
                                        false,
                                        now(),
                                        transactionId,
                                        reason = (throwable.message ?: "Unknown error")
                                    )
                                }
                            }
                        }
                    } else {
                        val body = try {
                            mapper.readValue(response.body(), ExternalSysResponse::class.java)
                        } catch (e: Exception) {
                            ExternalSysResponse(
                                transactionId.toString(),
                                paymentId.toString(),
                                false,
                                e.message ?: "Parse error"
                            )
                        }

                        logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")
                        paymentESService.update(paymentId) {
                            it.logProcessing(body.result, now(), transactionId, reason = body.message)
                        }
                    }
                } finally {
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