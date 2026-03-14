package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import kotlin.random.Random


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    meterRegistry: MeterRegistry,
    private val parallelLimiter: Semaphore,
    private val rateLimiter: SlidingWindowRateLimiter
) : PaymentExternalSystemAdapter {

    private val startedRequests =
        meterRegistry.counter("payment.processing.started", "accountName", properties.accountName)
    private val requestsRetried =
        meterRegistry.counter("payment.request.retried", "accountName", properties.accountName)
    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val processingTimeMillis = 1000L
    private val time95Percentile = processingTimeMillis / 3
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests
    private val inFlightRequests = AtomicInteger(0)
    private val retryRequests = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        meterRegistry.gauge(
            "payment.account.inflight.requests",
            listOf(Tag.of("accountName", properties.accountName)),
            inFlightRequests
        ) { it.toDouble() }
        meterRegistry.gauge(
            "payment.account.retry.requests",
            listOf(Tag.of("accountName", properties.accountName)),
            retryRequests
        ) { it.toDouble() }
    }

    private val timer =
        meterRegistry.timer("payment.external.system.request.latency", "accountName", properties.accountName)
    private val retryCounter =
        meterRegistry.counter("payment.external.retry.count", "accountName", properties.accountName)
    private val retryExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(100)
    private val httpClient = HttpClient
        .newBuilder()
        .executor(Executors.newFixedThreadPool(parallelRequests))
        .version(HttpClient.Version.HTTP_2)
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")
        val transactionId = UUID.randomUUID()

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
//            try {
//                paymentESService.update(paymentId) {
//                    it.logSubmission(
//                        success = true,
//                        transactionId,
//                        now(),
//                        Duration.ofMillis(now() - paymentStartedAt)
//                    )
//                }
//                logger.info("[$accountName] Log submission recorded for $paymentId")
//            } catch (e: Exception) {
//                logger.error("[$accountName] Failed to record log submission for $paymentId", e)
//            }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        val remaining = deadline - now()
        val minRequiredTime = requestAverageProcessingTime.toMillis()
        if (remaining < minRequiredTime) {
            logger.warn("[$accountName] Not enough time for payment $paymentId: ${remaining}ms remaining, need ${minRequiredTime}ms")
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, "not enough time")
            }
            return
        }

        val request = HttpRequest.newBuilder()
            .uri(URI("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"))
            .timeout(Duration.ofMillis(time95Percentile))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        completeAction(0, request, paymentId, transactionId, deadline)
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    fun completeAction(
        retryCount: Long,
        request: HttpRequest,
        paymentId: UUID,
        transactionId: UUID,
        deadline: Long
    ) {
        parallelLimiter.acquire()
        inFlightRequests.incrementAndGet()
        val timeBeforeCall = now()
        startedRequests.increment()
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, throwable ->
                parallelLimiter.release()
                    if (throwable != null) {
                        val e = throwable.cause
                        var isRetriable = true
                        when (throwable.cause) {
                            is SocketTimeoutException -> {
                                logger.warn("[$accountName] attempt ${retryCount + 1} timeout: $paymentId", e)
                            }

                            is InterruptedIOException -> {
                                logger.warn("[$accountName] interrupted: $paymentId", e)
                            }

                            is EOFException -> {
                                logger.warn("[$accountName] eof exception in: $paymentId", e)
                            }

                            is IOException -> {
                                logger.warn("[$accountName] received stream exception in: $paymentId", e)
                            }

                            else -> {
                                logger.warn("[$accountName] io error: $paymentId", e)
                                isRetriable = false
                            }
                        }

                        if (retryCount + 1 >= 3) {
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, "Max attempts reached")
                            }
                        } else {
                            val backoff = ((2.0.pow(retryCount.toDouble()) * 25).toLong() + Random.nextLong(10))
                            val capped = backoff.coerceAtMost(deadline - now() - 5)
                            if (capped <= 0) {
                                paymentESService.update(paymentId) {
                                    it.logProcessing(false, now(), transactionId, "Deadline expired")
                                }
                            } else {
                                if (isRetriable) {
                                    scheduleRetry(
                                        retryCount, request, paymentId, transactionId, deadline, capped
                                    )
                                } else {
                                    paymentESService.update(paymentId) {
                                        it.logProcessing(false, now(), transactionId, "Non-retriable exception")
                                    }
                                }
                            }
                        }
                    } else {
                        try {
                            logger.warn("Free space in semaphore: {}", parallelLimiter.availablePermits())
                            logger.info(
                                "success in callback for payment: {}, retry count: {}, in time: {}",
                                paymentId,
                                retryCount,
                                now()
                            )
                            val rawBody = response.body()
                            val parsed = try {
                                mapper.readValue(rawBody, ExternalSysResponse::class.java)
                            } catch (ex: Exception) {
                                ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, ex.message)
                            }

                            paymentESService.update(paymentId) {
                                it.logProcessing(parsed.result, now(), transactionId, parsed.message)
                            }
                            timer.record(now() - timeBeforeCall, TimeUnit.MILLISECONDS)
                        } catch (e: Exception) {
                            logger.error("[$accountName] Error processing payment $paymentId", e)
                        }
                    }
            }
    }

    private fun scheduleRetry(
        retryCount: Long, request: HttpRequest, paymentId: UUID,
        transactionId: UUID, deadline: Long, delay: Long
    ) {
        requestsRetried.increment()
        retryExecutor.schedule({
            logger.info("Completing retry. All retry count - {}", requestsRetried.count())
            val remainingTime = deadline - now()
            if (remainingTime < requestAverageProcessingTime.toMillis()) {
                try {
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, "Not enough time for retry")
                    }
                    return@schedule
                } catch (e: Exception) {
                    logger.error("[$accountName] Failed to record retry failure for $paymentId", e)
                }
            } else {
                val newRequestTimeout = remainingTime.coerceIn(time95Percentile, requestAverageProcessingTime.toMillis() * 2)
                val newRequest = HttpRequest.newBuilder()
                    .uri(request.uri())
                    .timeout(Duration.ofMillis(newRequestTimeout))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()
                completeAction(retryCount + 1, newRequest, paymentId, transactionId, deadline)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }


    companion object {
        val logger: Logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()
    }
}


fun now() = System.currentTimeMillis()