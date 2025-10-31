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
    @Qualifier("parallelLimiter")
    fun parallelLimiter(accountProperties: PaymentAccountProperties): Semaphore {
        return Semaphore(accountProperties.parallelRequests)
    }
}
