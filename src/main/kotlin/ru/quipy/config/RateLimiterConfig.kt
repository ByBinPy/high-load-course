package ru.quipy.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.quipy.common.utils.LeakingBucketRateLimiter
import java.time.Duration

@Configuration
class RateLimiterConfig {

    @Bean
    fun paymentRateLimiter(): LeakingBucketRateLimiter {
        return LeakingBucketRateLimiter(
            rate = 9,
            window = Duration.ofSeconds(1),
            bucketSize = 11
        )
    }
}