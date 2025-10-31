package ru.quipy.payments.logic

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import ru.quipy.common.utils.CallerBlockingRejectedExecutionHandler
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.time.Duration
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    private val paymentExecutor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            accountProperties.parallelRequests,
            accountProperties.parallelRequests,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(accountProperties.parallelRequests),
            NamedThreadFactory("payment-submission-executor"),
            CallerBlockingRejectedExecutionHandler(maxWait = Duration.ofMillis(1500))
        )
    }

    private val rateLimit: SlidingWindowRateLimiter by lazy {
        SlidingWindowRateLimiter(
            rate = accountProperties.rateLimitPerSec.toLong(),
            window = Duration.ofMillis(1000),
        )
    }

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        paymentProcessingPlannedCounter.increment()
        while (!rateLimit.tick()) {
            Thread.sleep(Random().nextLong(1, 10))
        }
        val createdAt = System.currentTimeMillis()

        paymentProcessingStartedCounter.increment()
        paymentExecutor.submit {
            try {
                val createdEvent = paymentESService.create {
                    it.create(paymentId, orderId, amount)
                }
                logger.trace("Payment ${createdEvent.paymentId} for order $orderId created.")
                paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
            } catch (e: Exception) {
                parallelLimiter.release()
                paymentProcessingCompletedCounter.increment()
                throw e
            } finally {
                parallelLimiter.release()
                paymentProcessingCompletedCounter.increment()

            }
        }
        return createdAt
    }
}

/**
 *     "serviceName": "cas-m3404",
 *     "accountName": "acc-23",
 *     "parallelRequests": 64,
 *     "rateLimitPerSec": 11,
 *     "price": 30,
 *     "averageProcessingTime": "PT1S"

 *   "accounts": "acc-23",
 *   "ratePerSecond": 15,
 *   "testCount": 3000,
 *   "processingTimeMillis": 2500
 * }
 */