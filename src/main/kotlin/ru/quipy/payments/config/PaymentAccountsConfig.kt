package ru.quipy.payments.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.quipy.common.utils.CallerBlockingRejectedExecutionHandler
import ru.quipy.common.utils.LeakingBucketRateLimiter
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.logic.PaymentAccountProperties
import ru.quipy.payments.logic.PaymentAggregateState
import ru.quipy.payments.logic.PaymentExternalSystemAdapter
import ru.quipy.payments.logic.PaymentExternalSystemAdapterImpl
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit


@Configuration
class PaymentAccountsConfig {
    companion object {
        private val javaClient = HttpClient.newBuilder().build()
        private val mapper = ObjectMapper().registerKotlinModule().registerModules(JavaTimeModule())
    }

    var rateCheckWindow: Duration = Duration.ofMillis(1000)

    @Value("\${payment.hostPort}")
    lateinit var paymentProviderHostPort: String

    @Value("\${payment.service-name}")
    lateinit var serviceName: String

    @Value("\${payment.token}")
    lateinit var token: String

    @Value("#{'\${payment.accounts}'.split(',')}")
    lateinit var allowedAccounts: List<String>

    @Bean
    fun warehouseIfUnfinishedWork(
        accountProperties: List<PaymentAccountProperties>,
        @Value("\${payment.maximumPoolSize}")
        maximumPoolSize: Int
    ): ThreadPoolExecutor {
        val poolSize = 100
        val temp = ThreadPoolExecutor(
            poolSize,
            poolSize,
            0,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(maximumPoolSize),
            NamedThreadFactory("payment-submission-executor"),
            CallerBlockingRejectedExecutionHandler()
        )
        return temp
    }

    @Bean
    fun parallelLimiter(
        accountProperties: List<PaymentAccountProperties>
    ): Semaphore =
        Semaphore(accountProperties.minOf { it.parallelRequests })

    @Bean
    fun burstRateLimiter(
        accountProperties: List<PaymentAccountProperties>,
        @Value("#{'\${payment.processingTimeMillis}'.split(',')}")
        processingTimeMillis: Int
    ): LeakingBucketRateLimiter =
        LeakingBucketRateLimiter(
            rate = accountProperties.minOf { it.rateLimitPerSec }.toLong(),
            window = rateCheckWindow,
            bucketSize = (((processingTimeMillis - accountProperties.maxOf { it.averageProcessingTime }
                .toMillis()) / accountProperties.maxOf { it.averageProcessingTime }
                .toMillis()) * accountProperties.minOf { it.rateLimitPerSec }.toLong()).toInt()
        )

    @Bean
    fun smoothOutIncoming(
        accountProperties: List<PaymentAccountProperties>,
    ): SlidingWindowRateLimiter =
        SlidingWindowRateLimiter(
            rate = accountProperties.minOf { it.rateLimitPerSec }.toLong(),
            window = rateCheckWindow,
        )


    @Bean
    fun accountProperties(): List<PaymentAccountProperties> {
        val request = HttpRequest.newBuilder()
            .uri(URI("http://${paymentProviderHostPort}/external/accounts?serviceName=$serviceName&token=$token"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()

        val resp = javaClient.send(request, HttpResponse.BodyHandlers.ofString())

        val accounts: List<PaymentAccountProperties> = mapper.readValue<List<PaymentAccountProperties>>(
            resp.body(),
            mapper.typeFactory.constructCollectionType(
                List::class.java,
                PaymentAccountProperties::class.java
            )
        ).filter { it.accountName in allowedAccounts }

        return accounts
    }

    @Bean
    fun accountAdapters(
        paymentService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
        meterRegistry: MeterRegistry,
        accountProperties: List<PaymentAccountProperties>,
        rateLimiter: SlidingWindowRateLimiter
    ): List<PaymentExternalSystemAdapter> {
        return accountProperties
            .map { it.copy(enabled = true) }
            .onEach(::println)
            .map {
                PaymentExternalSystemAdapterImpl(
                    it,
                    paymentService,
                    paymentProviderHostPort,
                    token,
                    meterRegistry,
                    parallelLimiter(accountProperties),
                    rateLimiter
                )
            }
    }
}