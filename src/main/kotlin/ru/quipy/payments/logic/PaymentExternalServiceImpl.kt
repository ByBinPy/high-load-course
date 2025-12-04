package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.sync.Semaphore
import okhttp3.*
import org.slf4j.LoggerFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit


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
    private val startedRequests = meterRegistry.counter("payment.processing.started", "accountName", properties.accountName)
    private val timer = meterRegistry.timer("payment.external.system.request.latency", "accountName", properties.accountName)
    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val dispatcher = Dispatcher().apply {
        maxRequestsPerHost = parallelRequests
        maxRequests = parallelRequests * 2
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(parallelRequests, 6, TimeUnit.MINUTES))
        .callTimeout(30_000, TimeUnit.MILLISECONDS)
        .build()


    override suspend fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")
        val transactionId = UUID.randomUUID()

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")
            val remaining = deadline - now()
            if (remaining <= 0) {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "deadline expired")
                }
                return
            }

            val timeBeforeCall = now()
            remaining.coerceAtMost(30_000L)
            tryAcquire(now(), remaining)
            val request = Request.Builder()
                .url(
                    "http://$paymentProviderHostPort/external/process" +
                            "?serviceName=$serviceName&token=$token&accountName=$accountName" +
                            "&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"
                )
                .post(emptyBody)
                .build()

        logger.info("Client connections {}. Semaphore was locked: {} ", client.connectionPool.connectionCount(), parallelRequests-parallelLimiter.availablePermits)
        client.newCall(request).enqueue(PaymentCallback(
                startedRequests,
                parallelLimiter,
                accountName,
                0,
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

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    fun tryAcquire(startedAt: Long, remaining: Long): Boolean {
        while (!parallelLimiter.tryAcquire() && now()-startedAt < remaining) { }
        return parallelLimiter.tryAcquire()
    }


}
fun now() = System.currentTimeMillis()