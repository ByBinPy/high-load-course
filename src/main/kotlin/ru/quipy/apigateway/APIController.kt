package ru.quipy.apigateway

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.quipy.common.utils.RateLimiter
import ru.quipy.orders.repository.OrderRepository
import ru.quipy.payments.logic.OrderPayer
import java.util.UUID
import kotlin.random.Random

@RestController
class APIController(val rateLimiter: RateLimiter) {
    val logger: Logger = LoggerFactory.getLogger(APIController::class.java)

    @Autowired
    private lateinit var orderRepository: OrderRepository

    @Autowired
    private lateinit var orderPayer: OrderPayer

    @PostMapping("/users")
    fun createUser(@RequestBody req: CreateUserRequest): User {
        return User(UUID.randomUUID(), req.name)
    }

    data class CreateUserRequest(val name: String, val password: String)

    data class User(val id: UUID, val name: String)

    @PostMapping("/orders")
    fun createOrder(@RequestParam userId: UUID, @RequestParam price: Int): Order {
        val order = Order(
            UUID.randomUUID(),
            userId,
            System.currentTimeMillis(),
            OrderStatus.COLLECTING,
            price,
        )
        return orderRepository.save(order)
    }

    data class Order(
        val id: UUID,
        val userId: UUID,
        val timeCreated: Long,
        val status: OrderStatus,
        val price: Int,
    )

    enum class OrderStatus {
        COLLECTING, PAYMENT_IN_PROGRESS, PAID,
    }

    @PostMapping("/orders/{orderId}/payment")
    fun payOrder(@PathVariable orderId: UUID, @RequestParam deadline: Long): PaymentSubmissionDto {
        while (true) {
            if (rateLimiter.tick()) {
                val paymentId = UUID.randomUUID()
                val order = orderRepository.findById(orderId)?.let {
                    orderRepository.save(it.copy(status = OrderStatus.PAYMENT_IN_PROGRESS))
                    it
                } ?: throw IllegalArgumentException("No such order $orderId")
                val createdAt = orderPayer.processPayment(orderId, order.price, paymentId, deadline)
                return PaymentSubmissionDto(createdAt, paymentId)
            }
        }
    }

    private fun waitProgress() = Thread.sleep(Random.nextLong(1000, 2000))

    class PaymentSubmissionDto(
        val timestamp: Long, val transactionId: UUID
    )
}