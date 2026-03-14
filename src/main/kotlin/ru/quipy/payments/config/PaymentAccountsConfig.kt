package ru.quipy.payments.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.Logger
import org.slf4j.LoggerFactory
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
    val logger: Logger = LoggerFactory.getLogger(PaymentAccountsConfig::class.java)

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
        meterRegistry: io.micrometer.core.instrument.MeterRegistry,
    ): ThreadPoolExecutor {
        val corePoolSize = 1000
        val maximumPoolSize = 1000
        val queueSize = 100_000
        val keepAliveTime = 0
        logger.info("Thread Pool Properties: core pool size - {}, maximum pool size - {}, queue size - {}, keepAliveTime - {}", corePoolSize, maximumPoolSize, queueSize, keepAliveTime)
        val executor = ThreadPoolExecutor(
            corePoolSize,
            maximumPoolSize,
            0,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(100_000),
            NamedThreadFactory("payment-submission-executor"),
            CallerBlockingRejectedExecutionHandler()
        )

        meterRegistry.gauge("payment.executor.active.tasks", executor) { it.activeCount.toDouble() }
        meterRegistry.gauge("payment.executor.queue.size", executor) { it.queue.size.toDouble() }

        return executor
    }

    @Bean
    fun parallelLimiter(
        accountProperties: List<PaymentAccountProperties>
    ): Semaphore {
        val parallelRequests = accountProperties.minOf { it.parallelRequests }
        logger.info("Semaphore Properties: permits count - {}", parallelRequests)
        return Semaphore(parallelRequests)
    }

    @Bean
    fun burstRateLimiter(
        accountProperties: List<PaymentAccountProperties>,
        @Value("#{'\${payment.processingTimeMillis}'.split(',')}")
        processingTimeMillis: Int
    ): LeakingBucketRateLimiter {
        val bucketSize = 6000
        val rate = accountProperties.minOf { it.rateLimitPerSec }.toLong()
        logger.info("Burst Rate Limiter Properties: bucket size - {}, rate - {}", bucketSize, rate)
        return LeakingBucketRateLimiter(
            rate = rate,
            window = rateCheckWindow,
            bucketSize = bucketSize
        )
    }

    @Bean
    fun smoothOutIncoming(
        accountProperties: List<PaymentAccountProperties>,
    ): SlidingWindowRateLimiter {
        val rate = accountProperties.minOf { it.rateLimitPerSec }.toLong()
        logger.info("Incoming Rate Limiter Properties: rate - {}", rate)
        return SlidingWindowRateLimiter(
            rate = rate,
            window = rateCheckWindow,
        )
    }

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
        meterRegistry: io.micrometer.core.instrument.MeterRegistry,
        accountProperties: List<PaymentAccountProperties>,
        rateLimiter: SlidingWindowRateLimiter
    ): List<PaymentExternalSystemAdapter> {
        return accountProperties
            .map {
                if (it.accountName == "acc-22") {
                    it.copy(enabled = true, hedgingEnabled = true, hedgeDelayMillis = 400L)
                } else {
                    it.copy(enabled = true)
                }
            }
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