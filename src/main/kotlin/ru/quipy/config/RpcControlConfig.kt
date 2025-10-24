package ru.quipy.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.quipy.common.utils.CompositeRateLimiter
import ru.quipy.common.utils.LeakingBucketRateLimiter
import ru.quipy.common.utils.RateLimiter
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.common.utils.TokenBucketRateLimiter
import ru.quipy.payments.logic.PaymentAccountProperties
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@Configuration
class RpcControlConfig {

    @Bean
    fun getRateLimiter(accountProperties: PaymentAccountProperties): RateLimiter =
        TokenBucketRateLimiter(6, 11, 500, TimeUnit.MILLISECONDS )

    @Bean
    @Qualifier("parallelLimiter")
    fun parallelLimiter(accountProperties: PaymentAccountProperties): Semaphore {
        return Semaphore(accountProperties.parallelRequests)
    }
}
