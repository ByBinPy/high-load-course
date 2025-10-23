package ru.quipy.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.quipy.common.utils.CompositeRateLimiter
import ru.quipy.common.utils.LeakingBucketRateLimiter
import ru.quipy.common.utils.RateLimiter
import ru.quipy.common.utils.TokenBucketRateLimiter
import ru.quipy.payments.logic.PaymentAccountProperties
import java.time.Duration
import java.util.concurrent.Semaphore

@Configuration
class RpcControlConfig {

    @Bean
    fun getRateLimiter(accountProperties: PaymentAccountProperties): RateLimiter =
        CompositeRateLimiter(
            TokenBucketRateLimiter(
                accountProperties.rateLimitPerSec, accountProperties.parallelRequests,
                Duration.ofSeconds(1).toMillis()
            ), LeakingBucketRateLimiter(
                accountProperties.rateLimitPerSec.toLong(),
                Duration.ofMillis(1),
                accountProperties.parallelRequests
            )
        )

    @Bean
    @Qualifier("parallelLimiter")
    fun parallelLimiter(accountProperties: PaymentAccountProperties): Semaphore {
        return Semaphore(accountProperties.parallelRequests)
    }
}
