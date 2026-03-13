package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.exceptions.TooManyRequestsException
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

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val time95Percentile = 40;
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

    private val processingTimeMillis = 1500L
    private val timer =
        meterRegistry.timer("payment.external.system.request.latency", "accountName", properties.accountName)
    private val retryCounter =
        meterRegistry.counter("payment.external.retry.count", "accountName", properties.accountName)
    private val retryExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(properties.parallelRequests)
    private val httpClient = HttpClient
        .newBuilder()
        .executor(Executors.newFixedThreadPool(parallelRequests))
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofMillis(processingTimeMillis / 2))
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        try {
            scope.launch {
                paymentESService.update(paymentId) {
                    it.logSubmission(
                        success = true,
                        transactionId,
                        now(),
                        Duration.ofMillis(now() - paymentStartedAt)
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("[$accountName] Failed to record log submission for $paymentId", e)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"))
            //.timeout(Duration.ofMillis(time95Percentile))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        parallelLimiter.acquire()
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
        inFlightRequests.incrementAndGet()
        val timeBeforeCall = now()
//        if (!rateLimiter.tickBlocking(timeout = deadline - now() - time95Percentile)) {
//            parallelLimiter.release()
//            throw TooManyRequestsException(10)
//        }
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, throwable ->
                parallelLimiter.release()
                inFlightRequests.decrementAndGet()
                if (retryCount > 0) {
                    retryRequests.decrementAndGet()
                }
                timer.record(now() - timeBeforeCall, TimeUnit.MILLISECONDS)
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
                        scope.launch {
                            updateWithRetry(paymentId, false, now(), "Max attempts reached")
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, "Max attempts reached")
                            }
                        }
                    } else {
                        val backoff = ((2.0.pow(retryCount.toDouble()) * 25).toLong() + Random.nextLong(10))
                        val capped = backoff.coerceAtMost(deadline - now() - 5)
                        if (capped <= 0) {
                            scope.launch {
                                updateWithRetry(paymentId, false, now(), "Deadline exceeded, no time for retry")
                            }
                        } else {
                            if (isRetriable) {
                                retryRequests.incrementAndGet()
                                retryCounter.increment()
                                scheduleRetry(
                                    retryCount, request, paymentId, transactionId, deadline, capped
                                )
                            } else {
                                scope.launch {
                                    updateWithRetry(paymentId, false, now(), "Non-retriable exception")
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
                        scope.launch {
                            updateWithRetry(paymentId, parsed.result, now(), parsed.message ?: "No message")
                        }
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
        retryExecutor.schedule({
            val remainingTime = deadline - now()
            if (remainingTime < requestAverageProcessingTime.toMillis()) {
                retryRequests.decrementAndGet()
                try {
                    scope.launch {
                        updateWithRetry(paymentId, false, now(), "Not enough time for retry")
                    }
                } catch (e: Exception) {
                    logger.error("[$accountName] Failed to record retry failure for $paymentId", e)
                }
            } else {
                //val newRequestTimeout = remainingTime.coerceIn(time95Percentile, requestAverageProcessingTime.toMillis() * 2)
                val newRequest = HttpRequest.newBuilder()
                    .uri(request.uri())
                    //.timeout(Duration.ofMillis(newRequestTimeout))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()
                parallelLimiter.acquire()
                completeAction(retryCount + 1, newRequest, paymentId, transactionId, deadline)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private suspend fun updateWithRetry(
        paymentId: UUID,
        result: Boolean,
        processedAt: Long,
        reason: String,
        maxAttempts: Int = 5,
    ) {
        repeat(maxAttempts) { attempt ->
            try {
                paymentESService.update(paymentId) {
                    it.logProcessing(result, processedAt, paymentId, reason)
                }
            } catch (_: Exception) {
                if (attempt == maxAttempts - 1) {
                    return
                }
                val delay = (10L * (attempt + 1)) + Random.nextLong(5)
                delay(delay)
            }
        }
    }

    companion object {
        val logger: Logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()
    }
}


fun now() = System.currentTimeMillis()