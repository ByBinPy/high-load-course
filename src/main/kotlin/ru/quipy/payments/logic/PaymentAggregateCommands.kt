package ru.quipy.payments.logic

import io.micrometer.core.instrument.Metrics
import ru.quipy.payments.api.PaymentCreatedEvent
import ru.quipy.payments.api.PaymentProcessedEvent
import ru.quipy.payments.api.PaymentSubmittedEvent
import java.time.Duration
import java.util.*


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
    val submission = transactionId?.let { this.submissions[it] }
    val submittedAt = submission?.timeStarted ?: 0
    val spentInQueueDuration = submission?.spentInQueue ?: Duration.ofMillis(0)
    Metrics.timer("bombardier.in.queue.latency").record(spentInQueueDuration)
    return PaymentProcessedEvent(
        this.getId(), success, this.orderId, submittedAt, processedAt, this.amount!!, transactionId, reason, spentInQueueDuration
    )
}