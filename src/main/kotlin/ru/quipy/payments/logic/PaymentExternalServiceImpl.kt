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
import java.util.concurrent.atomic.AtomicBoolean
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

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val connectTimeoutMillis = 200L
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests
    private val inFlightRequests = AtomicInteger(0)
    private val retryRequests = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.Default)

    private val hedgeEnabled = properties.hedgingEnabled
    private val hedgeDelayMillis = properties.hedgeDelayMillis ?: (requestAverageProcessingTime.toMillis() / 2).coerceAtLeast(50L)

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
    private val retryExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(30)
    private val httpClient = HttpClient
        .newBuilder()
        .executor(Executors.newFixedThreadPool(parallelRequests))
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()

        val remainingTimeAtStart = deadline - now()
        if (remainingTimeAtStart <= 50L) {
            try {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "Deadline already expired")
                }
            } catch (e: Exception) {
                logger.error("[$accountName] Failed to record deadline expiry for $paymentId", e)
            }
            return
        }

        val initialTimeout = remainingTimeAtStart

        val baseUri =
            "http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"
        val request = HttpRequest.newBuilder()
            .uri(URI(baseUri))
            .timeout(Duration.ofMillis(initialTimeout))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val completed = AtomicBoolean(false)

        if (!rateLimiter.tickBlocking(50)) {
            try {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "Rate limit exceeded at start")
                }
            } catch (e: Exception) {
                logger.error("[$accountName] Failed to record rate limit for $paymentId", e)
            }
            return
        }

        parallelLimiter.acquire()
        sendAttempt(0, request, paymentId, transactionId, deadline, completed, allowHedge = true)

        if (hedgeEnabled) {
            val hedgeDelays = listOf(hedgeDelayMillis, hedgeDelayMillis * 2)
            for (delay in hedgeDelays) {
                try {
                    retryExecutor.schedule({
                        try {
                            if (completed.get()) return@schedule
                            val remainingBeforeHedge = deadline - now()
                            if (remainingBeforeHedge <= 100L) return@schedule

                            val hedgeTimeout = remainingBeforeHedge
                            val hedgeRequest = HttpRequest.newBuilder()
                                .uri(URI(baseUri))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofMillis(hedgeTimeout))
                                .build()

                            if (!rateLimiter.tickBlocking(15)) {
                                return@schedule
                            }

                            parallelLimiter.acquire()
                            sendAttempt(
                                0,
                                hedgeRequest,
                                paymentId,
                                transactionId,
                                deadline,
                                completed,
                                allowHedge = false
                            )
                        } catch (e: Exception) {
                            logger.warn("[$accountName] Failed to send hedged request for $paymentId", e)
                        }
                    }, delay, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    logger.warn("[$accountName] Failed to schedule hedged request", e)
                }
            }
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    fun sendAttempt(
        retryCount: Long,
        request: HttpRequest,
        paymentId: UUID,
        transactionId: UUID,
        deadline: Long,
        completed: AtomicBoolean,
        allowHedge: Boolean
    ) {
        inFlightRequests.incrementAndGet()
        val timeBeforeCall = now()
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, throwable ->
                parallelLimiter.release()
                inFlightRequests.decrementAndGet()
                if (retryCount > 0) {
                    retryRequests.decrementAndGet()
                }
                timer.record(now() - timeBeforeCall, TimeUnit.MILLISECONDS)

                if (completed.get()) {
                    return@whenComplete
                }

                if (throwable != null) {
                    val e = throwable.cause
                    var isRetriable = true
                    when (throwable.cause) {
                        is SocketTimeoutException -> {
                        }

                        is InterruptedIOException -> {
                        }

                        is EOFException -> {
                        }

                        is IOException -> {
                        }

                        else -> {
                            logger.warn("[$accountName] io error: $paymentId", e)
                            isRetriable = false
                        }
                    }

                    if (retryCount + 1 >= 3) {
                        if (completed.compareAndSet(false, true)) {
                            try {
                                paymentESService.update(paymentId) {
                                    it.logProcessing(false, now(), transactionId, "Max attempts reached")
                                }
                            } catch (ex: Exception) {
                                logger.error("[$accountName] Failed to record max attempts for $paymentId", ex)
                            }
                        }
                    } else {
                        val backoff = ((2.0.pow(retryCount.toDouble()) * 25).toLong() + Random.nextLong(10))
                        val capped = backoff.coerceAtMost(deadline - now() - 5)
                        if (capped <= 0) {
                            if (completed.compareAndSet(false, true)) {
                                try {
                                    paymentESService.update(paymentId) {
                                        it.logProcessing(false, now(), transactionId, "Deadline expired")
                                    }
                                } catch (ex: Exception) {
                                    logger.error("[$accountName] Failed to record deadline expiry for $paymentId", ex)
                                }
                            }
                        } else {
                            if (isRetriable) {
                                if (!completed.get()) {
                                    retryRequests.incrementAndGet()
                                    retryCounter.increment()
                                    scheduleRetry(
                                        retryCount, request, paymentId, transactionId, deadline, capped, completed
                                    )
                                }
                            } else {
                                if (completed.compareAndSet(false, true)) {
                                    try {
                                        paymentESService.update(paymentId) {
                                            it.logProcessing(false, now(), transactionId, "Non-retriable exception")
                                        }
                                    } catch (ex: Exception) {
                                        logger.error("[$accountName] Failed to record non-retriable for $paymentId", ex)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    try {
                        val rawBody = response.body()
                        val parsed = try {
                            mapper.readValue(rawBody, ExternalSysResponse::class.java)
                        } catch (ex: Exception) {
                            ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, ex.message)
                        }
                        if (completed.compareAndSet(false, true)) {
                            try {
                                paymentESService.update(paymentId) {
                                    it.logProcessing(parsed.result, now(), transactionId, parsed.message)
                                }
                            } catch (e: Exception) {
                                logger.error("[$accountName] Error updating ES for payment $paymentId", e)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("[$accountName] Error processing payment $paymentId", e)
                    }
                }
            }
    }

    private fun scheduleRetry(
        retryCount: Long, request: HttpRequest, paymentId: UUID,
        transactionId: UUID, deadline: Long, delay: Long, completed: AtomicBoolean
    ) {
        retryExecutor.schedule({
            if (completed.get()) return@schedule
            val remainingTime = deadline - now()
            if (remainingTime < 100L) {
                try {
                    if (completed.compareAndSet(false, true)) {
                        paymentESService.update(paymentId) {
                            it.logProcessing(
                                false,
                                now(),
                                transactionId,
                                "Not enough time for retry"
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.error("[$accountName] Failed to record retry failure for $paymentId", e)
                }
            } else {
                val newRequestTimeout = remainingTime.coerceAtLeast(50L)
                val newRequest = HttpRequest.newBuilder()
                    .uri(request.uri())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofMillis(newRequestTimeout))
                    .build()
                if (!rateLimiter.tick()) {
                    if (completed.compareAndSet(false, true)) {
                        try {
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, "Rate limit prevented retry")
                            }
                        } catch (e: Exception) {
                            logger.error("[$accountName] Failed to record rate-limited retry for $paymentId", e)
                        }
                    }
                    return@schedule
                }
                parallelLimiter.acquire()
                sendAttempt(
                    retryCount + 1,
                    newRequest,
                    paymentId,
                    transactionId,
                    deadline,
                    completed,
                    allowHedge = false
                )
            }
        }, delay, TimeUnit.MILLISECONDS)
    }


    companion object {
        val logger: Logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()
    }
}


fun now() = System.currentTimeMillis()