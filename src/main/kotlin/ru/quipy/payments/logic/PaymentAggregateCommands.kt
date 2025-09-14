package ru.quipy.payments.logic

import ru.quipy.payments.api.PaymentCreatedEvent
import ru.quipy.payments.api.PaymentProcessedEvent
import ru.quipy.payments.api.PaymentSubmittedEvent
import java.time.Duration
import java.util.UUID


fun PaymentAggregateState.create(id: UUID, orderId: UUID, amount: Int): PaymentCreatedEvent {
    return PaymentCreatedEvent(
        paymentId = id,
        orderId = orderId,
        amount = amount,
    )
}

fun PaymentAggregateState.logSubmission(success: Boolean, transactionId: UUID, startedAt: Long, spentInQueueDuration: Duration): PaymentSubmittedEvent {
    return PaymentSubmittedEvent(
        this.getId(), success, this.orderId, transactionId, startedAt, spentInQueueDuration
    )
}

fun PaymentAggregateState.logProcessing(
    success: Boolean,
    processedAt: Long,
    transactionId: UUID? = null,
    reason: String? = null
): PaymentProcessedEvent {
    val submittedAt = this.submissions[transactionId ?: UUID.randomUUID()]?.timeStarted ?: 0
    val spentInQueueDuration = this.submissions[transactionId ?: UUID.randomUUID()]?.spentInQueue ?: Duration.ofMillis(0)

    return PaymentProcessedEvent(
        this.getId(), success, this.orderId, submittedAt, processedAt, this.amount!!, transactionId, reason, spentInQueueDuration
    )
}