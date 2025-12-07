package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.sync.Semaphore
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random


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
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val maxRequestTimeout = 20_000L
    // 2025-11-20T20:30:35.780+03:00  INFO 56644 --- [alhost:1234/...] ru.quipy.core.EventSourcingService       : Optimistic lock exception. Failed to save event records id: [7dca693e-e811-4b7f-8bce-23e13d952c04-4]
    private val startedRequests = meterRegistry.counter("payment.processing.started", "accountName", properties.accountName)
    private val timer = meterRegistry.timer("payment.external.system.request.latency", "accountName", properties.accountName)
    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val rateLimit: SlidingWindowRateLimiter by lazy {
        SlidingWindowRateLimiter(
            rate = rateLimitPerSec.toLong(),
            window = Duration.ofMillis(1000),
        )
    }

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

        if (!tryAcquire(now(), deadline - now())) {
            val retryAfterMs = (requestAverageProcessingTime.toMillis() / parallelRequests * 10).coerceIn(10, 100) + Random.nextLong(10)

            throw TooManyRequestsException(retryAfterMs)
        }

        if (!rateLimit.tickBlockingWithTimeout(deadline - now())) {
            parallelLimiter.release()

            val retryAfterMs = (1000L / rateLimitPerSec * 10).coerceIn(10, 100) + Random.nextLong(10)
            throw TooManyRequestsException(retryAfterMs)
        }

        val requestTimeout = minOf(
            deadline - now(),
            requestAverageProcessingTime.toMillis() * 2
        ).coerceAtLeast(100)

        val request = HttpRequest.newBuilder()
            .uri(URI("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"))
            .timeout(Duration.ofMillis(requestTimeout))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val retryCount = 0L

        completeAction(retryCount, request, paymentId, transactionId, timeBeforeCall, deadline)
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    fun tryAcquire(startedAt: Long, remaining: Long): Boolean {
        var isAcquired = parallelLimiter.tryAcquire()
        while (!isAcquired && now() - startedAt < remaining) {
            isAcquired = parallelLimiter.tryAcquire()
        }

        return isAcquired
    }

    fun completeAction(retryCount: Long, request: HttpRequest, paymentId: UUID, transactionId: UUID, timeBeforeCall: Long, deadline: Long) {
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, throwable ->
                    if (throwable != null) {
                        val e = throwable.cause
                        when (throwable.cause) {
                            is SocketTimeoutException -> {
                                logger.warn("[$accountName] attempt ${retryCount + 1} timeout: $paymentId", e)
                                paymentESService.update(paymentId) {
                                    it.logProcessing(false, now(), transactionId, "socket timeout")
                                }
                            }

                            is InterruptedIOException -> {
                                logger.warn("[$accountName] interrupted: $paymentId", e)

                                // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                                // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                                paymentESService.update(paymentId) {
                                    it.logProcessing(false, now(), transactionId, "interrupted IO")
                                }
                            }

                            else -> {
                                logger.warn("[$accountName] io error: $paymentId", e)
                                paymentESService.update(paymentId) {
                                    it.logProcessing(false, now(), transactionId, "io exception")
                                }
                            }
                        }

                        if (retryCount + 1 >= 3) {
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, "Max attempts reached")
                            }
                            startedRequests.increment()
                            parallelLimiter.release()
                        } else {
                            val backoff = ((2.0.pow(retryCount.toDouble()) * 25).toLong() + kotlin.random.Random.nextLong(10))
                            val capped = backoff.coerceAtMost(deadline - now() - 5)
                            if (capped <= 0) {
                                paymentESService.update(paymentId) {
                                    it.logProcessing(false, now(), transactionId, "Deadline expired")
                                }
                                startedRequests.increment()
                                parallelLimiter.release()
                            } else {
                                Thread.sleep(capped)
                                completeAction(
                                    retryCount + 1,
                                    request,
                                    paymentId,
                                    transactionId,
                                    timeBeforeCall,
                                    deadline
                                )
                            }
                        }
                    } else {
                        startedRequests.increment()
                        try {
                            logger.warn("Free space in semaphore: {}", parallelLimiter.availablePermits)
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
                        } finally {
                            parallelLimiter.release()
                            timer.record(now() - timeBeforeCall, TimeUnit.MILLISECONDS)
                        }
                    }
                }
            }
    }

fun now() = System.currentTimeMillis()