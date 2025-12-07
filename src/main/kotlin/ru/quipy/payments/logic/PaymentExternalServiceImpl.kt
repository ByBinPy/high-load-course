package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    meterRegistry: MeterRegistry,
    private val parallelLimiter: Semaphore,
    private val ioDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors() * 2,
        NamedThreadFactory("payment-io-")
    ).asCoroutineDispatcher()
) : PaymentExternalSystemAdapter {
    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.error("[$accountName] Unhandled exception in payment adapter coroutine", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + exceptionHandler)

    private val startedRequests =
        meterRegistry.counter("payment.processing.started", "accountName", properties.accountName)
    private val timer =
        meterRegistry.timer("payment.external.system.request.latency", "accountName", properties.accountName)
    private val successCounter =
        meterRegistry.counter("payment.processing.success", "accountName", properties.accountName)
    private val failureCounter =
        meterRegistry.counter("payment.processing.failure", "accountName", properties.accountName)
    private val timeoutCounter =
        meterRegistry.counter("payment.processing.timeout", "accountName", properties.accountName)

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
        .executor(Executors.newFixedThreadPool(parallelRequests))
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val retryScheduler = Executors.newScheduledThreadPool(
        Runtime.getRuntime().availableProcessors()
    )

    private val pendingRequests = Collections.synchronizedMap(mutableMapOf<UUID, ScheduledFuture<*>>())

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.info("[$accountName] Submitting payment request for payment $paymentId")
        val transactionId = UUID.randomUUID()

        scope.launch {
            try {
                withTimeout(deadline - System.currentTimeMillis()) {
                    paymentESService.update(paymentId) {
                        it.logSubmission(
                            success = true,
                            transactionId,
                            now(),
                            Duration.ofMillis(now() - paymentStartedAt)
                        )
                    }
                }
                logger.info("[$accountName] Log submission recorded for $paymentId")
            } catch (e: Exception) {
                logger.error("[$accountName] Failed to record log submission for $paymentId", e)
            }
        }

        scope.launch {
            try {
                executePaymentWithRetries(paymentId, transactionId, amount, deadline)
            } catch (e: Exception) {
                logger.error("[$accountName] Failed to process payment $paymentId", e)
                handlePaymentFailure(paymentId, transactionId, e)
            }
        }
    }

    private suspend fun executePaymentWithRetries(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int,
        deadline: Long
    ) {
        var retryCount = 0
        val maxRetries = 3

        while (retryCount <= maxRetries) {
            try {
                checkDeadline(deadline, paymentId)

                val result = executePaymentAttempt(paymentId, transactionId, amount, deadline)

                if (result) {
                    successCounter.increment()
                    return
                } else {
                    retryCount++
                    if (retryCount <= maxRetries) {
                        val backoff = calculateBackoff(retryCount, deadline)
                        delay(backoff)
                    }
                }
            } catch (e: TooManyRequestsException) {
                throw e
            } catch (e: CancellationException) {
                logger.warn("[$accountName] Payment $paymentId was cancelled")
                throw e
            } catch (e: Exception) {
                logger.warn("[$accountName] Attempt ${retryCount + 1} failed for payment $paymentId", e)
                retryCount++

                if (retryCount > maxRetries) {
                    throw e
                }

                val backoff = calculateBackoff(retryCount, deadline)
                delay(backoff)
            }
        }

        throw RuntimeException("All retry attempts exhausted for payment $paymentId")
    }

    private suspend fun executePaymentAttempt(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int,
        deadline: Long
    ): Boolean = withTimeoutOrNull(deadline - System.currentTimeMillis()) {
        try {
            val semaphoreAcquired = withTimeoutOrNull(deadline - System.currentTimeMillis()) {
                parallelLimiter.tryAcquire()
                if (!parallelLimiter.tryAcquire()) {
                    val waitTime = minOf(deadline - System.currentTimeMillis(), 100L)
                    if (waitTime > 0) {
                        delay(waitTime)
                    }
                    parallelLimiter.tryAcquire()
                } else {
                    true
                }
            } ?: false

            if (!semaphoreAcquired) {
                val retryAfter = (requestAverageProcessingTime.toMillis() / parallelRequests).coerceIn(10, 100)
                throw TooManyRequestsException(retryAfter + Random.nextLong(10))
            }

            val rateLimitAcquired = withTimeoutOrNull(deadline - System.currentTimeMillis()) {
                rateLimit.tickBlockingWithTimeout(deadline - System.currentTimeMillis())
            } ?: false

            if (!rateLimitAcquired) {
                parallelLimiter.release()
                val retryAfter = (1000L / rateLimitPerSec).coerceIn(10, 100)
                throw TooManyRequestsException(retryAfter + Random.nextLong(10))
            }

            val requestTimeout = calculateRequestTimeout(deadline)

            val response = executeHttpRequest(paymentId, transactionId, amount, requestTimeout)

            val processingResult = processHttpResponse(response, paymentId, transactionId)

            if (processingResult) {
                paymentESService.update(paymentId) {
                    it.logProcessing(true, now(), transactionId, "Success")
                }
            }

            return@withTimeoutOrNull processingResult

        } finally {
            if (parallelLimiter.availablePermits < parallelRequests) {
                parallelLimiter.release()
            }
            startedRequests.increment()
        }
    } ?: run {
        timeoutCounter.increment()
        logger.warn("[$accountName] Payment $paymentId timed out")
        false
    }

    private suspend fun executeHttpRequest(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int,
        requestTimeout: Long
    ): HttpResponse<String> = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"))
            .timeout(Duration.ofMillis(requestTimeout))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        try {
            val timeBeforeCall = now()
            val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).get()
            timer.record(now() - timeBeforeCall, TimeUnit.MILLISECONDS)
            response
        } catch (e: Exception) {
            logger.error("[$accountName] HTTP request failed for $paymentId", e)
            throw e
        }
    }

    private fun processHttpResponse(
        response: HttpResponse<String>,
        paymentId: UUID,
        transactionId: UUID
    ): Boolean {
        return try {
            val rawBody = response.body()
            val parsed = mapper.readValue(rawBody, ExternalSysResponse::class.java)

            if (!parsed.result) {
                logger.warn("[$accountName] External system returned failure for $paymentId: ${parsed.message}")
                failureCounter.increment()
            }

            parsed.result
        } catch (ex: Exception) {
            logger.error("[$accountName] Failed to parse response for $paymentId", ex)
            failureCounter.increment()
            false
        }
    }

    private fun checkDeadline(deadline: Long, paymentId: UUID) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) {
            throw CancellationException("Deadline expired for payment $paymentId")
        }

        val minRequiredTime = requestAverageProcessingTime.toMillis() * 2
        if (remaining < minRequiredTime) {
            logger.warn("[$accountName] Not enough time for payment $paymentId: ${remaining}ms remaining, need ${minRequiredTime}ms")
            throw TooManyRequestsException(minRequiredTime - remaining + Random.nextLong(100))
        }
    }

    private fun calculateRequestTimeout(deadline: Long): Long {
        val remaining = deadline - System.currentTimeMillis()
        return minOf(
            remaining * 2 / 3,
            requestAverageProcessingTime.toMillis() * 3
        ).coerceIn(100, 30000L)
    }

    private fun calculateBackoff(retryCount: Int, deadline: Long): Long {
        val baseDelay = (2.0.pow(retryCount.toDouble()) * 25).toLong()
        val jitter = Random.nextLong(10)
        val totalDelay = baseDelay + jitter

        val remaining = deadline - System.currentTimeMillis()
        val safeDelay = minOf(totalDelay, remaining / 2)

        return safeDelay.coerceAtLeast(0)
    }

    private fun handlePaymentFailure(paymentId: UUID, transactionId: UUID, cause: Exception) {
        scope.launch {
            try {
                paymentESService.update(paymentId) {
                    val message = when (cause) {
                        is TooManyRequestsException -> "Rate limited"
                        is TimeoutCancellationException -> "Timeout"
                        is CancellationException -> "Cancelled"
                        else -> cause.message ?: "Unknown error"
                    }
                    it.logProcessing(false, now(), transactionId, message)
                }
            } catch (e: Exception) {
                logger.error("[$accountName] Failed to record failure for payment $paymentId", e)
            }
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    fun shutdown() {
        logger.info("[$accountName] Shutting down payment adapter")
        scope.cancel()
        retryScheduler.shutdown()
        (httpClient.executor().orElse(null) as? java.util.concurrent.ExecutorService)?.shutdown()
    }
}

fun now() = System.currentTimeMillis()