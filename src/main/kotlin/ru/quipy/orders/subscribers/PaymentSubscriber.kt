package ru.quipy.orders.subscribers

import io.micrometer.core.instrument.Metrics
import jakarta.annotation.PostConstruct
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.OnlineShopApplication.Companion.appExecutor
import ru.quipy.orders.repository.OrderRepository
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.api.PaymentProcessedEvent
import ru.quipy.streams.AggregateSubscriptionsManager
import ru.quipy.streams.annotation.RetryConf
import ru.quipy.streams.annotation.RetryFailedStrategy
import java.time.Duration

@Service
class PaymentSubscriber {

    val logger: Logger = LoggerFactory.getLogger(PaymentSubscriber::class.java)

    val paymentSucceededCounter = Metrics.counter("succeeded.payments", "account", "acc-5")

    @Autowired
    lateinit var subscriptionsManager: AggregateSubscriptionsManager

    @Autowired
    private lateinit var orderRepository: OrderRepository

    @PostConstruct
    fun init() {
        subscriptionsManager.createSubscriber(
            PaymentAggregate::class,
            "orders:payment-subscriber",
            retryConf = RetryConf(1, RetryFailedStrategy.SKIP_EVENT)
        ) {
            `when`(PaymentProcessedEvent::class) { event ->
                appExecutor.submit {
                    logger.trace(
                        "Payment results. OrderId ${event.orderId}, succeeded: ${event.success}, txId: ${event.transactionId}, reason: ${event.reason}, duration: ${
                            Duration.ofMillis(
                                event.createdAt - event.submittedAt
                            ).toSeconds()
                        }, spent in queue: ${event.spentInQueueDuration.toSeconds()}"
                    )
                    paymentSucceededCounter.increment()
                }
            }
        }
    }
}