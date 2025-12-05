package ru.quipy.payments.logic

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.Semaphore

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


     suspend fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
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