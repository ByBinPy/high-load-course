package ru.quipy.payments.logic

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.common.utils.CallerBlockingRejectedExecutionHandler
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.exceptions.RateLimitWasBreached
import ru.quipy.exceptions.TooManyRequestsException
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
    private val rateLimit: SlidingWindowRateLimiter by lazy {
        SlidingWindowRateLimiter(
            rate = 8,
            window = Duration.ofMillis(1000),
        )
    }

    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    private lateinit var paymentService: PaymentService


    suspend fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        if (!rateLimit.tickBlocking(deadline- System.currentTimeMillis())) {
            throw TooManyRequestsException(deadline)
        }

        val createdEvent =  paymentESService.create {
                it.create(
                    paymentId,
                    orderId,
                    amount
                )
            }

        logger.trace("Payment ${createdEvent.paymentId} for order $orderId created.")

        paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)

        return createdAt
    }
}