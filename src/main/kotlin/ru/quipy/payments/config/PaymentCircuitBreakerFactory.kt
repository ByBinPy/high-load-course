package ru.quipy.payments.config

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import ru.quipy.payments.logic.PaymentAccountProperties
import java.time.Duration

object PaymentCircuitBreakerFactory {

    fun forAccount(props: PaymentAccountProperties): CircuitBreaker {
        val avgMs = props.averageProcessingTime.toMillis().coerceAtLeast(1L)
        val slowThreshold = Duration.ofMillis((avgMs * 5).coerceAtMost(3000L))

        val config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(20)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50f)
            .slowCallRateThreshold(80f)
            .slowCallDurationThreshold(slowThreshold)
            .waitDurationInOpenState(Duration.ofSeconds(3))
            .permittedNumberOfCallsInHalfOpenState(5)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build()

        return CircuitBreaker.of("cb-${props.accountName}", config)
    }
}