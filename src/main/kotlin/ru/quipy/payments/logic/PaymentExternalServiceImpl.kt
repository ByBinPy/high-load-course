package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.exceptions.TooManyRequestsException
import ru.quipy.payments.api.PaymentAggregate
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.pow
import kotlin.random.Random

class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    meterRegistry: MeterRegistry,
    private val parallelLimiter: Semaphore,
) : PaymentExternalSystemAdapter, AutoCloseable {

    companion object {
        private val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        private val mapper = ObjectMapper().registerKotlinModule()
    }

    private val sharedScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
        Runtime.getRuntime().availableProcessors() * 2,
        NamedThreadFactory("payment-shared-${properties.accountName}-")
    )

    private val ioDispatcher: CoroutineDispatcher = sharedScheduler.asCoroutineDispatcher()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.error("[${properties.accountName}] Unhandled exception in payment adapter coroutine", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + exceptionHandler)

    private val startedRequests = meterRegistry.counter("payment.processing.started", "accountName", properties.accountName)
    private val timer = meterRegistry.timer("payment.external.system.request.latency", "accountName", properties.accountName)

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val rateLimit: SlidingWindowRateLimiter by lazy {
        SlidingWindowRateLimiter(
            rate = (rateLimitPerSec * 0.9).toLong(),
            window = Duration.ofMillis(1000),
        )
    }

    private val httpClient = HttpClient
        .newBuilder()
        .executor(sharedScheduler)
        .version(HttpClient.Version.HTTP_2)
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()

        scope.launch {
            try {
                paymentESService.update(paymentId) {
                    it.logSubmission(
                        success = true,
                        transactionId,
                        now(),
                        Duration.ofMillis(now() - paymentStartedAt)
                    )
                }
                logger.info("[$accountName] Log submission recorded for $paymentId")
            } catch (e: Exception) {
                logger.error("[$accountName] Failed to record log submission for $paymentId", e)
            }
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        val remaining = deadline - now()
        val minRequiredTime = requestAverageProcessingTime.toMillis() * 2
        if (remaining < minRequiredTime) {
            logger.warn("[$accountName] Not enough time for payment $paymentId: ${remaining}ms remaining, need ${minRequiredTime}ms")
            scope.launch {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "not enough time")
                }
            }

            val retryAfterMs = minRequiredTime - remaining + Random.nextLong(100)
            throw TooManyRequestsException(retryAfterMs)
        }

        var acquired = false
        try {
            if (!tryAcquire(now(), deadline - now())) {
                val retryAfterMs = (requestAverageProcessingTime.toMillis() * 2).coerceAtLeast(100) + Random.nextLong(50)
                throw TooManyRequestsException(retryAfterMs)
            }
            acquired = true

            if (!rateLimit.tickBlockingWithTimeout(deadline - now())) {
                val retryAfterMs = (1100L / rateLimitPerSec).coerceAtLeast(100) + Random.nextLong(50)
                throw TooManyRequestsException(retryAfterMs)
            }

            val requestTimeout = minOf(
                deadline - now(),
                requestAverageProcessingTime.toMillis() * 2
            ).coerceAtLeast(100)

            if (requestTimeout < requestAverageProcessingTime.toMillis()) {
                logger.warn("[$accountName] Timeout too short for payment $paymentId: ${requestTimeout}ms")
                val retryAfterMs = requestAverageProcessingTime.toMillis() - requestTimeout + Random.nextLong(100)
                throw TooManyRequestsException(retryAfterMs)
            }

            val request = HttpRequest.newBuilder()
                .uri(URI("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"))
                .timeout(Duration.ofMillis(requestTimeout))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()

            completeAction(
                retryCount = 0,
                request = request,
                paymentId = paymentId,
                transactionId = transactionId,
                timeBeforeCall = now(),
                deadline = deadline,
                acquired = true
            )

        } catch (e: TooManyRequestsException) {
            if (acquired) {
                parallelLimiter.release()
                acquired = false
            }
            throw e
        } catch (e: Exception) {
            if (acquired) {
                parallelLimiter.release()
                acquired = false
            }
            logger.error("[$accountName] Unexpected error in performPaymentAsync for $paymentId", e)
            scope.launch {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "unexpected error: ${e.message}")
                }
            }
            throw e
        }
    }

    private fun tryAcquire(startedAt: Long, remaining: Long): Boolean {
        var isAcquired = parallelLimiter.tryAcquire()
        while (!isAcquired && now() - startedAt < remaining) {
            Thread.sleep(1)
            isAcquired = parallelLimiter.tryAcquire()
        }
        return isAcquired
    }

    private fun completeAction(
        retryCount: Long,
        request: HttpRequest,
        paymentId: UUID,
        transactionId: UUID,
        timeBeforeCall: Long,
        deadline: Long,
        acquired: Boolean
    ) {
        val requestTimeout = request.timeout().orElse(Duration.ofSeconds(10)).toMillis()
        val future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .orTimeout(requestTimeout, TimeUnit.MILLISECONDS)

        future.whenComplete { response, throwable ->
            try {
                if (throwable != null) {
                    val cause = throwable.cause
                    val isTimeout = cause is SocketTimeoutException || throwable is TimeoutException
                    val isInterrupted = cause is InterruptedIOException

                    val errorType = when {
                        isTimeout -> "timeout"
                        isInterrupted -> "interrupted"
                        else -> "io_error"
                    }
                    logger.warn("[$accountName] attempt ${retryCount + 1} $errorType: $paymentId", throwable)

                    if (retryCount >= 2) {
                        scope.launch {
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, "Max attempts reached")
                            }
                        }
                    } else {
                        val backoff = ((2.0.pow(retryCount.toDouble()) * 25).toLong() + Random.nextLong(10))
                        val capped = backoff.coerceAtMost(deadline - now() - 10)
                        if (capped <= 0) {
                            scope.launch {
                                paymentESService.update(paymentId) {
                                    it.logProcessing(false, now(), transactionId, "Deadline expired")
                                }
                            }
                        } else {
                            scheduleRetry(
                                retryCount = retryCount + 1,
                                request = request,
                                paymentId = paymentId,
                                transactionId = transactionId,
                                timeBeforeCall = timeBeforeCall,
                                deadline = deadline,
                                delay = capped,
                                acquired = acquired
                            )
                            return@whenComplete
                        }
                    }
                } else {
                    logger.debug("[$accountName] Free space in semaphore: ${parallelLimiter.availablePermits}")
                    logger.info(
                        "[$accountName] success for payment: {}, retry count: {}, in time: {}",
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

                    scope.launch {
                        paymentESService.update(paymentId) {
                            it.logProcessing(parsed.result, now(), transactionId, parsed.message)
                        }
                    }

                    timer.record(now() - timeBeforeCall, TimeUnit.MILLISECONDS)
                }
            } catch (e: Exception) {
                logger.error("[$accountName] Error in whenComplete for payment $paymentId", e)
                scope.launch {
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, "callback error: ${e.message}")
                    }
                }
            } finally {
                startedRequests.increment()
                if (acquired) {
                    parallelLimiter.release()
                }
            }
        }
    }

    private fun scheduleRetry(
        retryCount: Long,
        request: HttpRequest,
        paymentId: UUID,
        transactionId: UUID,
        timeBeforeCall: Long,
        deadline: Long,
        delay: Long,
        acquired: Boolean
    ) {
        sharedScheduler.schedule({
            val remainingTime = deadline - now()
            if (remainingTime < requestAverageProcessingTime.toMillis()) {
                scope.launch {
                    try {
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, "Not enough time for retry")
                        }
                    } catch (e: Exception) {
                        logger.error("[$accountName] Failed to record retry failure for $paymentId", e)
                    } finally {
                        startedRequests.increment()
                        if (acquired) parallelLimiter.release()
                    }
                }
                return@schedule
            }

            val newRequestTimeout = minOf(remainingTime, requestAverageProcessingTime.toMillis() * 2).coerceAtLeast(100)
            val newRequest = HttpRequest.newBuilder()
                .uri(request.uri())
                .timeout(Duration.ofMillis(newRequestTimeout))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()

            completeAction(
                retryCount = retryCount,
                request = newRequest,
                paymentId = paymentId,
                transactionId = transactionId,
                timeBeforeCall = timeBeforeCall,
                deadline = deadline,
                acquired = acquired
            )
        }, delay, TimeUnit.MILLISECONDS)
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    override fun close() {
        sharedScheduler.shutdown()
        try {
            if (!sharedScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                sharedScheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            sharedScheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}

fun now() = System.currentTimeMillis()