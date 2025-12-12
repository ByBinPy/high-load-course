package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.time.withTimeout
import org.slf4j.LoggerFactory
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
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toKotlinDuration


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    meterRegistry: MeterRegistry,
    val parallelLimiter: Semaphore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val startedRequests =
        meterRegistry.counter("payment.processing.started", "accountName", properties.accountName)
    private val timer =
        meterRegistry.timer("payment.external.system.request.latency", "accountName", properties.accountName)

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val rateLimiter: SlidingWindowRateLimiter by lazy {
        SlidingWindowRateLimiter(
            rate = (rateLimitPerSec * 0.95).toLong(),
            window = Duration.ofMillis(1000),
        )
    }

    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        scope.launch {
            val transactionId = UUID.randomUUID()
            val timeBeforeCall = now()

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

            logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

            val remaining = deadline - now()
            val minRequiredTime = requestAverageProcessingTime.toMillis() * 2
            if (remaining < minRequiredTime) {
                logger.warn("[$accountName] Not enough time for payment $paymentId: ${remaining}ms remaining, need ${minRequiredTime}ms")
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "not enough time")
                }
                val retryAfterMs = minRequiredTime - remaining + Random.nextLong(100)
                throw TooManyRequestsException(retryAfterMs)
            }

            val acquired = tryAcquireWithDeadline(deadline)
            if (!acquired) {
                val retryAfterMs = (requestAverageProcessingTime.toMillis() / parallelRequests * 10)
                    .coerceIn(10L, 100L) + Random.nextLong(10)
                throw TooManyRequestsException(retryAfterMs)
            }

            val allowed = rateLimiter.tickBlockingWithTimeout(deadline - now())
            if (!allowed) {
                parallelLimiter.release()
                val retryAfterMs = (1000L / rateLimitPerSec * 10).coerceIn(10, 100) + Random.nextLong(10)
                throw TooManyRequestsException(retryAfterMs)
            }

            val requestTimeout = minOf(
                deadline - now(),
                requestAverageProcessingTime.toMillis() * 2
            ).coerceAtLeast(100)

            if (requestTimeout < requestAverageProcessingTime.toMillis()) {
                logger.warn("[$accountName] Timeout too short for payment $paymentId: ${requestTimeout}ms")
                parallelLimiter.release()
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
                timeBeforeCall = timeBeforeCall,
                deadline = deadline
            )
        }
    }

    private suspend fun tryAcquireWithDeadline(deadline: Long): Boolean {
        val timeoutMs = (deadline - now()).coerceAtLeast(0)
        return try {
            withTimeout(timeoutMs.milliseconds) {
                parallelLimiter.acquire()
                true
            }
        } catch (e: TimeoutCancellationException) {
            false
        }
    }

    private suspend fun completeAction(
        retryCount: Int,
        request: HttpRequest,
        paymentId: UUID,
        transactionId: UUID,
        timeBeforeCall: Long,
        deadline: Long
    ) {
        var success = false
        try {
            val response = withContext(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }

            val rawBody = response.body()
            val parsed = try {
                mapper.readValue(rawBody, ExternalSysResponse::class.java)
            } catch (ex: Exception) {
                ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, ex.message)
            }

            paymentESService.update(paymentId) {
                it.logProcessing(parsed.result, now(), transactionId, parsed.message)
            }
            timer.record(now() - timeBeforeCall, java.util.concurrent.TimeUnit.MILLISECONDS)
            startedRequests.increment()
            success = true

        } catch (e: Exception) {
            when (e) {
                is java.net.http.HttpTimeoutException,
                is SocketTimeoutException -> {
                    logger.warn("[$accountName] attempt ${retryCount + 1} timeout: $paymentId", e)
                }
                is InterruptedIOException -> {
                    logger.warn("[$accountName] interrupted: $paymentId", e)
                }
                else -> {
                    logger.warn("[$accountName] io error: $paymentId", e)
                }
            }

            if (retryCount + 1 >= 3) {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "Max attempts reached")
                }
                startedRequests.increment()
            } else {
                val backoff = ((2.0.pow(retryCount.toDouble()) * 25).toLong() + Random.nextLong(10))
                val capped = backoff.coerceAtMost(deadline - now() - 5)

                if (capped <= 0) {
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, "Deadline expired")
                    }
                    startedRequests.increment()
                } else {
                    delay(capped)
                    val newTimeout = minOf(
                        deadline - now(),
                        requestAverageProcessingTime.toMillis() * 2
                    ).coerceAtLeast(100)

                    val newRequest = HttpRequest.newBuilder()
                        .uri(request.uri())
                        .timeout(Duration.ofMillis(newTimeout))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build()

                    completeAction(
                        retryCount + 1,
                        newRequest,
                        paymentId,
                        transactionId,
                        timeBeforeCall,
                        deadline
                    )
                    return
                }
            }
        } finally {
            if (!success) {
                parallelLimiter.release()
            }
        }
    }

    override fun price() = properties.price
    override fun isEnabled() = properties.enabled
    override fun name() = properties.accountName
}

fun now() = System.currentTimeMillis()