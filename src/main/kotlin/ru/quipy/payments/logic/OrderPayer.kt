package ru.quipy.payments.logic

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
class OrderPayer(
    val rateLimiter: SlidingWindowRateLimiter,
    @Qualifier("warehouseIfUnfinishedWork")
    val paymentExecutor: ThreadPoolExecutor,
    meterRegistry: MeterRegistry,
    accountProperties: List<PaymentAccountProperties>
    ) {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    val inExecTimer = meterRegistry.timer("order.payer.exec.latency", "accountName", accountProperties.joinToString { it.accountName + " " })

    private val plannedCounter: Counter = meterRegistry.counter("payment.processing.planned")
    private val startedCounter: Counter = meterRegistry.counter("payment.processing.started")
    private val completedCounter: Counter = meterRegistry.counter("payment.processing.completed")

    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    private lateinit var paymentService: PaymentService

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        plannedCounter.increment()

        CompletableFuture
            .runAsync(
                {
                    startedCounter.increment()
                    paymentESService.create {
                        it.create(paymentId, orderId, amount)
                    }
                },
                paymentExecutor,
            )
            .thenRunAsync(
                {
                    inExecTimer.record(now() - createdAt, TimeUnit.MILLISECONDS)
                    paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
                },
                paymentExecutor,
            )
            .whenComplete { _, ex ->
                if (ex != null) {
                    logger.warn("process stopped before submit for payment: $paymentId", ex)
                }
                completedCounter.increment()
            }

        return createdAt
    }
}