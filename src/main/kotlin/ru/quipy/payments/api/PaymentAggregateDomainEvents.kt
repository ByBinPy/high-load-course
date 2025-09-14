package ru.quipy.payments.api

import ru.quipy.core.annotations.DomainEvent
import ru.quipy.domain.Event
import java.time.Duration
import java.util.UUID

const val PAYMENT_CREATED_EVENT = "PAYMENT_CREATED_EVENT"
const val PAYMENT_SUBMITTED_EVENT = "PAYMENT_SUBMITTED_EVENT"
const val PAYMENT_PROCESSED_EVENT = "PAYMENT_PROCESSED_EVENT"

@DomainEvent(name = PAYMENT_CREATED_EVENT)
class PaymentCreatedEvent(
    val paymentId: UUID,
    val orderId: UUID,
    val amount: Int,
    createdAt: Long = System.currentTimeMillis(),
) : Event<PaymentAggregate>(
    name = PAYMENT_CREATED_EVENT,
    createdAt = createdAt,
)

@DomainEvent(name = PAYMENT_SUBMITTED_EVENT)
class PaymentSubmittedEvent(
    val paymentId: UUID,
    val success: Boolean,
    val orderId: UUID,
    val transactionId: UUID,
    val startedAt: Long,
    val spentInQueueDuration: Duration,
    createdAt: Long = System.currentTimeMillis(),
) : Event<PaymentAggregate>(
    name = PAYMENT_SUBMITTED_EVENT,
    createdAt = createdAt,
)

@DomainEvent(name = PAYMENT_PROCESSED_EVENT)
class PaymentProcessedEvent(
    val paymentId: UUID,
    val success: Boolean,
    val orderId: UUID,
    val submittedAt: Long,
    val processedAt: Long,
    val amount: Int,
    val transactionId: UUID?,
    val reason: String?,
    val spentInQueueDuration: Duration,
    createdAt: Long = System.currentTimeMillis(),
) : Event<PaymentAggregate>(
    name = PAYMENT_PROCESSED_EVENT,
    createdAt = createdAt,
)