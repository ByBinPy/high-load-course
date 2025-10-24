package ru.quipy.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.quipy.common.utils.CompositeRateLimiter
import ru.quipy.common.utils.RateLimiter
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.common.utils.TokenBucketRateLimiter
import ru.quipy.payments.logic.PaymentAccountProperties
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@Configuration
class RpcControlConfig {

    @Bean
    fun getRateLimiter(accountProperties: PaymentAccountProperties): RateLimiter =
        CompositeRateLimiter(SlidingWindowRateLimiter(accountProperties.rateLimitPerSec.toLong() * 30, Duration.ofSeconds(30)), TokenBucketRateLimiter(11, 120, 1000, TimeUnit.MILLISECONDS ))

    @Bean
    @Qualifier("parallelLimiter")
    fun parallelLimiter(accountProperties: PaymentAccountProperties): Semaphore {
        return Semaphore(accountProperties.parallelRequests)
    }
}
