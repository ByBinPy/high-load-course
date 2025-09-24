package ru.quipy.payments.logic

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
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

    private val paymentExecutor = ThreadPoolExecutor(
        30,
        30,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(150),
        NamedThreadFactory("payment-submission-executor"),
        CallerBlockingRejectedExecutionHandler()
    )

    private val rateLimit = SlidingWindowRateLimiter(
        rate = 10,
        window = Duration.ofSeconds(1),
    )

    private val parallelLimiter = Semaphore(30)

    suspend fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()

        parallelLimiter.acquire()

        return try {
            while(!rateLimit.tick()) {
                delay(10)
            }

            paymentExecutor.submit {
                try {
                    val createdEvent = paymentESService.create {
                        it.create(paymentId, orderId, amount)
                    }
                    logger.trace("Payment ${createdEvent.paymentId} for order $orderId created.")
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