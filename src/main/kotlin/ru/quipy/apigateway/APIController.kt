package ru.quipy.apigateway

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import ru.quipy.common.utils.LeakingBucketRateLimiter
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.common.utils.TokenBucketRateLimiter
import ru.quipy.exceptions.TooManyRequestsException
import ru.quipy.orders.repository.OrderRepository
import ru.quipy.payments.logic.OrderPayer
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

@RestController
class APIController(private val orderRepository: OrderRepository, private val orderPayer: OrderPayer) {

    val logger: Logger = LoggerFactory.getLogger(APIController::class.java)

    @PostMapping("/users")
    fun createUser(@RequestBody req: CreateUserRequest): User {
        return User(UUID.randomUUID(), req.name)
    }

    data class CreateUserRequest(val name: String, val password: String)

    data class User(val id: UUID, val name: String)

    private val tokenBucketRateLimiter: TokenBucketRateLimiter by lazy {
        TokenBucketRateLimiter(
            rate = 3,
            bucketMaxCapacity = 80,
            window = 1000,
            startBucket = 80,
            timeUnit = TimeUnit.MILLISECONDS
        )
    }

    private val leakingBucketRateLimiter: LeakingBucketRateLimiter by lazy {
        LeakingBucketRateLimiter(
            rate = 3,
            window = Duration.ofSeconds(1),
            bucketSize = 3
        )
    }


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
        COLLECTING,
        PAYMENT_IN_PROGRESS,
        PAID,
    }

    @PostMapping("/orders/{orderId}/payment")
    fun payOrder(@PathVariable orderId: UUID, @RequestParam deadline: Long): PaymentSubmissionDto {

        if (!tokenBucketRateLimiter.tick()) {
            throw TooManyRequestsException(retryAfterMillisecond = 2_500)
        }

        val paymentId = UUID.randomUUID()
        val order = orderRepository.findById(orderId)?.let {
            orderRepository.save(it.copy(status = OrderStatus.PAYMENT_IN_PROGRESS))
            it
        } ?: throw IllegalArgumentException("No such order $orderId")

        val createdAt = orderPayer.processPayment(orderId, order.price, paymentId, deadline)
        return PaymentSubmissionDto(createdAt, paymentId)
    }

    class PaymentSubmissionDto(
        val timestamp: Long,
        val transactionId: UUID
    )
}