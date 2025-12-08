package ru.quipy.payments.logic

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*

@Service
class OrderPayer(meterRegistry: MeterRegistry) {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    private val plannedRequests = meterRegistry.counter("payment.processing.planned", "accountName", "acc-12")


    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    private lateinit var paymentService: PaymentService


    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
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

        return createdAt
    }
}