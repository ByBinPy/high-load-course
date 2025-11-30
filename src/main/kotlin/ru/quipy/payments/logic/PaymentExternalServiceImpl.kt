package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.MeterRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.IOException
import org.slf4j.LoggerFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.pow


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    meterRegistry: MeterRegistry,
    private val parallelLimiter: Semaphore
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val timer = meterRegistry.timer("payment.external.system.request.latency", "accountName", properties.accountName)
    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val client = OkHttpClient.Builder()
        .callTimeout(requestAverageProcessingTime.toMillis() + 2000, TimeUnit.MILLISECONDS).build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        var retryCount = 0
        while (true) {
            val remaining = deadline - now()
            if (remaining <= 0) {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "deadline expired")
                }
                return
            }

            if (!parallelLimiter.tryAcquire(remaining, TimeUnit.MILLISECONDS)) {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "parallel limiter timeout")
                }
                return
            }

            val timeBeforeCall = now()
            var shouldRetry = false
            try {
                val perCallTimeoutMs = remaining.coerceAtMost(1100)
                val request = Request.Builder()
                    .url(
                        "http://$paymentProviderHostPort/external/process" +
                            "?serviceName=$serviceName&token=$token&accountName=$accountName" +
                            "&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"
                    )
                    .post(emptyBody)
                    .build()

                val call = client.newCall(request)
                call.timeout().timeout(perCallTimeoutMs, TimeUnit.MILLISECONDS)

                call.execute().use { response ->
                    val rawBody = response.body?.string()
                    val parsed = try {
                        mapper.readValue(rawBody, ExternalSysResponse::class.java)
                    } catch (ex: Exception) {
                        ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, ex.message)
                    }

                    shouldRetry = !parsed.result && (response.code == 429 || response.code >= 500)

                    paymentESService.update(paymentId) {
                        it.logProcessing(parsed.result, now(), transactionId, parsed.message)
                    }

                    if (parsed.result) {
                        return
                    }
                }
            } catch (e: SocketTimeoutException) {
                logger.warn("[$accountName] attempt ${retryCount + 1} timeout: $paymentId", e)
                shouldRetry = true
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "socket timeout")
                }
            } catch (e: InterruptedIOException) {
                logger.warn("[$accountName] interrupted: $paymentId", e)
                shouldRetry = true

                // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "interrupted IO")
                }
            } catch (e: IOException) {
                logger.warn("[$accountName] io error: $paymentId", e)
                shouldRetry = true
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "io exception")
                }
            } catch (e: Exception) {
                logger.error("[$accountName] non-retriable error: $paymentId", e)
                shouldRetry = false
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, e.message)
                }
            } finally {
                timer.record(now() - timeBeforeCall, TimeUnit.MILLISECONDS)
                parallelLimiter.release()
            }

            if (!shouldRetry) {
                return
            }

            retryCount++
            if (retryCount >= 3) {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "Max attempts reached")
                }
                return
            }

            val backoff = ((2.0.pow(retryCount.toDouble()) * 25).toLong() + kotlin.random.Random.nextLong(10))
            val capped = backoff.coerceAtMost(deadline - now() - 5)
            if (capped <= 0) {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "Deadline expired")
                }
                return
            }
            Thread.sleep(capped)
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName
    fun timeToDead(deadline: Long): Long {
        return deadline - now()
    }
}
public fun now() = System.currentTimeMillis()