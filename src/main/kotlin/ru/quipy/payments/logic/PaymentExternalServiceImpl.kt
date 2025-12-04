package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.IOException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.integration.IntegrationProperties
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
    // 2025-11-20T20:30:35.780+03:00  INFO 56644 --- [alhost:1234/...] ru.quipy.core.EventSourcingService       : Optimistic lock exception. Failed to save event records id: [7dca693e-e811-4b7f-8bce-23e13d952c04-4]
    private val timer = meterRegistry.timer("payment.external.system.request.latency", "accountName", properties.accountName)
    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val client = OkHttpClient.Builder()
        .callTimeout(1100, TimeUnit.MILLISECONDS).build()

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
            val perCallTimeoutMs = remaining.coerceAtMost(1100)
            val request = Request.Builder()
                .url(
                    "http://$paymentProviderHostPort/external/process" +
                            "?serviceName=$serviceName&token=$token&accountName=$accountName" +
                            "&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"
                )
                .post(emptyBody)
                .build()

            val call = client.newCall(request).enqueue(PaymentCallback(
                kotlinx.coroutines.sync.Semaphore(parallelRequests),
                accountName,
                retryCount,
                paymentId,
                transactionId,
                paymentESService,
                client,
                request,
                timer,
                deadline,
                timeBeforeCall
            ))
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