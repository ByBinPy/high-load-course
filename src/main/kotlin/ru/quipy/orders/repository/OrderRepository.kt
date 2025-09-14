package ru.quipy.orders.repository

import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.apigateway.APIController.Order
import ru.quipy.streams.AggregateSubscriptionsManager
import java.time.Duration
import java.util.UUID

@Service
class OrderRepository {

    val logger: Logger = LoggerFactory.getLogger(OrderRepository::class.java)

    @Autowired
    lateinit var subscriptionsManager: AggregateSubscriptionsManager

    private val orderCache = Caffeine.newBuilder()
        .maximumSize(100_000_000)
        .expireAfterWrite(Duration.ofHours(5))
        .build<UUID, Order?>()


    fun save(order: Order): Order {
        orderCache.put(order.id, order)
        return order
    }

    fun findById(id: UUID): Order? {
        return orderCache.getIfPresent(id)
    }
}
