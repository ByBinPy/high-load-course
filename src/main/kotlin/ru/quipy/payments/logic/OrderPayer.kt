package ru.quipy.payments.logic

import kotlinx.coroutines.delay
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.common.utils.CallerBlockingRejectedExecutionHandler
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.time.Duration
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
class OrderPayer {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    private lateinit var paymentService: PaymentService

    @Autowired
    private lateinit var accountAdapters: List<PaymentExternalSystemAdapter>

    private val accountProperties: PaymentAccountProperties by lazy {
        accountAdapters.firstOrNull()?.getAccountProperties()
            ?: throw IllegalStateException("No payment accounts configured")
    }

    private val paymentExecutor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            accountProperties.parallelRequests,
            accountProperties.parallelRequests,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(accountProperties.parallelRequests * 10),
            NamedThreadFactory("payment-submission-executor"),
            CallerBlockingRejectedExecutionHandler()
        )
    }

    private val rateLimit: SlidingWindowRateLimiter by lazy {
        SlidingWindowRateLimiter(
            rate = accountProperties.rateLimitPerSec.toLong(),
            window = Duration.ofSeconds(1),
        )
    }

    private val parallelLimiter = Semaphore(5)

    suspend fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()

        parallelLimiter.acquire()

        return try {
            while (!rateLimit.tick()) {
                delay(100)
            }

            paymentExecutor.submit {
                try {
                    val createdEvent = paymentESService.create {
                        it.create(paymentId, orderId, amount)
                    }
                    logger.trace("Payment {} for order {} created.", createdEvent.paymentId, orderId)
                    paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
                } finally {
                    parallelLimiter.release()
                }
            }
            createdAt
        } catch (e: Exception) {
            parallelLimiter.release()
            throw e
        }
    }
}