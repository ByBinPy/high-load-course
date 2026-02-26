package ru.quipy.payments.logic

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.exceptions.TooManyRequestsException
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Service
class OrderPayer(val rateLimiter : SlidingWindowRateLimiter, meterRegistry: MeterRegistry) {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    private val plannedRequests = meterRegistry.counter("payment.processing.planned", "accountName", "acc-13")

    private val paymentExecutor = ThreadPoolExecutor(
        4000,
        4000,
        100L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(200_000),
        NamedThreadFactory("payment-submission-executor")
    )

    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    private lateinit var paymentService: PaymentService


    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        if (!rateLimiter.tick()) {
            val retryAfterMs = (1000L / 1100 * 10).coerceIn(10, 100) + Random.nextLong(10)
            throw TooManyRequestsException(retryAfterMs)
        }
        paymentExecutor.submit {
            plannedRequests.increment()
            val createdEvent = paymentESService.create {
                it.create(
                    paymentId,
                    orderId,
                    amount
                )
            }

            logger.trace("Payment {} for order {} created.", createdEvent.paymentId, orderId)

            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
        }
        return createdAt
    }
}