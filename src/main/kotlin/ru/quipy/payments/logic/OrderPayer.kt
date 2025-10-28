package ru.quipy.payments.logic

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import ru.quipy.common.utils.CompositeRateLimiter
import ru.quipy.common.utils.LeakingBucketRateLimiter
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.common.utils.TokenBucketRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.exceptions.TooManyRequestsException
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.dto.Transaction
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import kotlin.compareTo
import kotlin.random.Random

@Service
class OrderPayer(
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentService: PaymentService,
    private val accountProperties: PaymentAccountProperties,
    @field:Qualifier("parallelLimiter")
    private val parallelLimiter: Semaphore,
) {
    private val paymentProcessingPlannedCounter: Counter =
        Metrics.counter("payment.processing.planned", "accountName", accountProperties.accountName)
    private val paymentProcessingStartedCounter: Counter =
        Metrics.counter("payment.processing.started", "accountName", accountProperties.accountName)
    private val paymentProcessingCompletedCounter: Counter =
        Metrics.counter("payment.processing.completed", "accountName", accountProperties.accountName)

    // Новая метрика отказов (rate limit)
    private val paymentProcessingRejectedCounter: Counter =
        Metrics.counter("payment.processing.rejected", "accountName", accountProperties.accountName)

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    private val paymentExecutor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            accountProperties.parallelRequests,
            accountProperties.parallelRequests,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue<Runnable>(accountProperties.parallelRequests * 2),
            NamedThreadFactory("payment-submission-executor"),
            ThreadPoolExecutor.CallerRunsPolicy()
        )
    }

    private val slidingWindowRateLimiter: SlidingWindowRateLimiter by lazy {
        SlidingWindowRateLimiter(
            rate = (accountProperties.rateLimitPerSec * 1.00).toLong(),
            window = Duration.ofMillis(500)
        )
    }

    private val tokenBucketRateLimiter: TokenBucketRateLimiter by lazy {
        TokenBucketRateLimiter(
            rate = accountProperties.rateLimitPerSec,
            bucketMaxCapacity = 140,
            window = 1,
            startBucket = 0,
            timeUnit = TimeUnit.SECONDS
        )
    }


    val leakingBucketRateLimiter = LeakingBucketRateLimiter(
        rate = 11,
        window = Duration.ofMillis(950),
        bucketSize = 50
    )

    private val compositeRateLimiter = CompositeRateLimiter(
        slidingWindowRateLimiter,
        tokenBucketRateLimiter,
        mode = CompositeRateLimiter.Mode.AND
    )

    private fun calculateRetryAfter(): Int {
        return when {
            accountProperties.rateLimitPerSec <= 3 -> 3_000
            accountProperties.rateLimitPerSec <= 11 -> 2_000
            else -> 1_000
        }
    }

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        paymentProcessingPlannedCounter.increment()

        if (!leakingBucketRateLimiter.tick()) {
            paymentProcessingRejectedCounter.increment()
            throw TooManyRequestsException(retryAfterSeconds = calculateRetryAfter())
        }

        val task = Runnable {
            parallelLimiter.acquire()
            paymentProcessingStartedCounter.increment()
            try {
                val createdEvent = paymentESService.create {
                    it.create(paymentId, orderId, amount)
                }
                logger.trace("Payment {} for order {} created.", createdEvent.paymentId, orderId)
                paymentService.submitPaymentRequest(
                    paymentId,
                    amount,
                    createdAt,
                    deadline
                )
            } finally {
                parallelLimiter.release()
                paymentProcessingCompletedCounter.increment()
            }
        }

        val transaction = Transaction(orderId, amount, paymentId, deadline, task)

        try {
            paymentExecutor.execute(transaction)
            return createdAt
        } catch (_: RejectedExecutionException) {
            paymentProcessingRejectedCounter.increment()
            throw TooManyRequestsException(retryAfterSeconds = calculateRetryAfter())
        }
    }
}
