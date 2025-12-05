package ru.quipy.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.payments.logic.PaymentAccountProperties
import java.time.Duration
import java.util.concurrent.Semaphore

@Configuration
class RpcControlConfig {

    @Bean
    fun getSlidingWindowRateLimiter(accountProperties: PaymentAccountProperties) =
        SlidingWindowRateLimiter(accountProperties.rateLimitPerSec.toLong(),
            Duration.ofSeconds(1)
        )

    @Bean
    @Qualifier("parallelLimiter")
    fun parallelLimiter(accountProperties: PaymentAccountProperties): Semaphore {
        return Semaphore(accountProperties.parallelRequests)
    }
}
