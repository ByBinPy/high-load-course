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
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random


class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    meterRegistry: MeterRegistry,
    private val parallelLimiter: Semaphore,
    private val ioDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors() * 4,
        NamedThreadFactory("payment-io-")
    ).asCoroutineDispatcher()
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.error("[${properties.accountName}] Unhandled exception", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + exceptionHandler)

    private val startedRequests =
        meterRegistry.counter("payment.processing.started", "accountName", properties.accountName)
    private val timer =
        meterRegistry.timer("payment.external.system.request.latency", "accountName", properties.accountName)

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAvg = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests


    private val rateLimit: SlidingWindowRateLimiter by lazy {
        SlidingWindowRateLimiter(
            rate = rateLimitPerSec.toLong(),
            window = Duration.ofSeconds(1)
        )
    }

    private val httpClient = HttpClient
        .newBuilder()
        .executor(Executors.newFixedThreadPool(parallelRequests * 2))
        .version(HttpClient.Version.HTTP_2)
        .build()

    private val retryScheduler = Executors.newScheduledThreadPool(
        Runtime.getRuntime().availableProcessors()
    )

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()

        scope.launch {
            try {
                paymentESService.update(paymentId) {
                    it.logSubmission(true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
                }
            } catch (_: Exception) {}
        }

        val startRemaining = deadline - now()
        val minRequired = requestAvg.toMillis() * 2

        if (startRemaining < minRequired) {
            val retryAfter = minRequired - startRemaining + Random.nextLong(20)
            throw TooManyRequestsException(retryAfter)
        }

        if (!tryAcquire(deadline)) {
            val retryAfter = (requestAvg.toMillis() / parallelRequests * 5)
                .coerceIn(20, 120) + Random.nextLong(20)
            throw TooManyRequestsException(retryAfter)
        }

        if (!rateLimit.tickBlockingWithTimeout(deadline - now())) {
            parallelLimiter.release()
            val retryAfter = (1000L / rateLimitPerSec * 5).coerceIn(20, 100) + Random.nextLong(20)
            throw TooManyRequestsException(retryAfter)
        }

        val reqTimeout = min(
            deadline - now(),
            requestAvg.toMillis() * 2
        ).coerceAtLeast(150)

        val request = HttpRequest.newBuilder()
            .uri(
                URI(
                    "http://$paymentProviderHostPort/external/process" +
                            "?serviceName=$serviceName&token=$token" +
                            "&accountName=$accountName&transactionId=$transactionId" +
                            "&paymentId=$paymentId&amount=$amount"
                )
            )
            .timeout(Duration.ofMillis(reqTimeout))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        completeAction(
            retryCount = 0,
            request = request,
            paymentId = paymentId,
            transactionId = transactionId,
            timeBeforeCall = now(),
            deadline = deadline
        )
    }

    override fun price() = properties.price
    override fun isEnabled() = properties.enabled
    override fun name() = accountName

    private fun tryAcquire(deadline: Long): Boolean {
        while (now() < deadline) {
            if (parallelLimiter.tryAcquire()) return true
            Thread.sleep(1)
        }
        return false
    }

    private fun completeAction(
        retryCount: Long,
        request: HttpRequest,
        paymentId: UUID,
        transactionId: UUID,
        timeBeforeCall: Long,
        deadline: Long
    ) {
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, throwable ->

                scope.launch {
                    try {
                        if (throwable != null) {
                            val cause = throwable.cause

                            val maxRetries = 3
                            val nextRetry = retryCount + 1

                            if (nextRetry >= maxRetries) {
                                paymentESService.update(paymentId) {
                                    it.logProcessing(false, now(), transactionId, "max retries reached")
                                }
                                return@launch
                            }

                            val remaining = deadline - now()
                            val need = requestAvg.toMillis()

                            if (remaining < need) {
                                paymentESService.update(paymentId) {
                                    it.logProcessing(false, now(), transactionId, "deadline expired")
                                }
                                return@launch
                            }

                            val delayMs = (25L * (1L shl retryCount.toInt())).coerceAtMost(80L)

                            scheduleRetry(
                                retryCount = nextRetry,
                                request = request,
                                paymentId = paymentId,
                                transactionId = transactionId,
                                timeBeforeCall = timeBeforeCall,
                                deadline = deadline,
                                delay = delayMs
                            )

                            return@launch
                        }

                        // успех
                        val body = response.body()
                        val parsed = try {
                            mapper.readValue(body, ExternalSysResponse::class.java)
                        } catch (ex: Exception) {
                            ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, ex.message)
                        }

                        paymentESService.update(paymentId) {
                            it.logProcessing(parsed.result, now(), transactionId, parsed.message)
                        }

                        timer.record(now() - timeBeforeCall, TimeUnit.MILLISECONDS)

                    } finally {
                        startedRequests.increment()
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
        delay: Long
    ) {
        retryScheduler.schedule({

            val remaining = deadline - now()
            val need = requestAvg.toMillis()

            if (remaining < need) {
                scope.launch {
                    try {
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, "deadline expired retry")
                        }
                    } finally {
                        startedRequests.increment()
                        parallelLimiter.release()
                    }
                }
                return@schedule
            }

            val timeout = min(remaining, requestAvg.toMillis() * 2).coerceAtLeast(100)

            val newReq = HttpRequest.newBuilder()
                .uri(request.uri())
                .timeout(Duration.ofMillis(timeout))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()

            completeAction(
                retryCount = retryCount,
                request = newReq,
                paymentId = paymentId,
                transactionId = transactionId,
                timeBeforeCall = timeBeforeCall,
                deadline = deadline
            )

        }, delay, TimeUnit.MILLISECONDS)
    }
}

fun now() = System.currentTimeMillis()
