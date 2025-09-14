package ru.quipy.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.quipy.common.utils.RateLimiter
import ru.quipy.common.utils.SlidingWindowRateLimiter
import java.time.Duration

@Configuration
class ApiConfig {
    @Bean
    fun getDefaultRateLimiter(): RateLimiter = SlidingWindowRateLimiter(8, Duration.ofSeconds(1))
}