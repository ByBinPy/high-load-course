package ru.quipy.payments.logic

import ru.quipy.core.annotations.StateTransitionFunc
import ru.quipy.domain.AggregateState
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.api.PaymentCreatedEvent
import ru.quipy.payments.api.PaymentProcessedEvent
import ru.quipy.payments.api.PaymentSubmittedEvent
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class PaymentAggregateState : AggregateState<UUID, PaymentAggregate> {
    private lateinit var paymentId: UUID

    var createdAt: Long = System.currentTimeMillis()
    var updatedAt: Long = System.currentTimeMillis()

    lateinit var orderId: UUID
    var amount: Int? = null

    var submissions = ConcurrentHashMap<UUID, PaymentSubmission>()
    var processings = ConcurrentHashMap<UUID, PaymentProcessingResult>()

    override fun getId() = paymentId

    @StateTransitionFunc
    fun paymentCreatedApply(event: PaymentCreatedEvent) {
        paymentId = event.paymentId
        orderId = event.orderId
        amount = event.amount
        updatedAt = createdAt
    }

    @StateTransitionFunc
    fun paymentSubmittedApply(event: PaymentSubmittedEvent) {
        submissions[event.transactionId] =
            PaymentSubmission(event.startedAt, event.transactionId, event.success, event.spentInQueueDuration)
        updatedAt = createdAt
    }

    @StateTransitionFunc
    fun paymentSubmittedApply(event: PaymentProcessedEvent) {
        processings[event.transactionId ?: UUID.randomUUID()] = PaymentProcessingResult(
            event.submittedAt,
            event.processedAt,
            event.transactionId,
            event.reason,
            event.success
        )
        updatedAt = createdAt
    }

    class PaymentSubmission(
        val timeStarted: Long,
        val transactionId: UUID,
        val success: Boolean,
        val spentInQueue: Duration,
    )

    class PaymentProcessingResult(
        val submittedAt: Long,
        val processedAt: Long,
        val transactionId: UUID?,
        val reason: String?,
        val success: Boolean,
    )
}