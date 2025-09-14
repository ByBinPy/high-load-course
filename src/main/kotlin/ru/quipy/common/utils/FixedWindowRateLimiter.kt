package ru.quipy.common.utils

import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

interface RateLimiter {
    fun tick(): Boolean
}

class FixedWindowRateLimiter(
    private val rate: Int,
    private val window: Long,
    private val timeUnit: TimeUnit = TimeUnit.MINUTES,
): RateLimiter {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(FixedWindowRateLimiter::class.java)
        private val counter = AtomicInteger(0)
    }

    private val rateLimiterScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private var semaphore = Semaphore(rate)
    private val semaphoreNumber = counter.getAndIncrement()

    private var start = System.currentTimeMillis()
    private var nextExpectedWakeUp = start + timeUnit.toMillis(window)


    private val releaseJob = rateLimiterScope.launch {
        while (true) {
            start = System.currentTimeMillis()
            nextExpectedWakeUp = start + timeUnit.toMillis(window)

            val permitsToRelease = rate - semaphore.availablePermits()
            repeat(permitsToRelease) {
                runCatching {
                    semaphore.release()
                }.onFailure { th -> logger.error("Failed while releasing permits", th) }
            }
            logger.trace("Semaphore ${semaphoreNumber}. Released $permitsToRelease permits")

            delay(nextExpectedWakeUp - System.currentTimeMillis())
        }
    }.invokeOnCompletion { th -> if (th != null) logger.error("Rate limiter release job completed", th) }

    override fun tick() = semaphore.tryAcquire()

    fun tickBlocking() = semaphore.acquire()
}

class SlowStartRateLimiter(
    private val targetRate: Int,
    private val timeUnit: TimeUnit = TimeUnit.MINUTES,
    private val slowStartOn: Boolean = true,
): RateLimiter {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(SlowStartRateLimiter::class.java)
        private val counter = AtomicInteger(0)
        private val rateLimiterScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    }

    @Volatile
    private var currentRate = if (slowStartOn) 1 else targetRate

    private val semaphore = Semaphore(targetRate).also { semaphore ->
        runBlocking {
            repeat(targetRate) {
                runCatching {
                    semaphore.acquire()
                }.onFailure { th -> logger.error("Failed while initially acquiring permits", th) }
            }
        }
    }
    private val rateLimiterNum = counter.getAndIncrement()

    private val releaseJob = rateLimiterScope.launch {
        while (true) {
            val start = System.currentTimeMillis()
            val permitsToRelease = currentRate - semaphore.availablePermits()
            repeat(permitsToRelease) {
                runCatching {
                    semaphore.release()
                }.onFailure { th -> logger.error("Failed while releasing permits", th) }
            }
            logger.trace("Rate limiter ${rateLimiterNum}. Released $permitsToRelease permits")

            if (slowStartOn && currentRate < targetRate) {
                currentRate = minOf(targetRate, currentRate * 2)
            }

            delay(timeUnit.toMillis(1) - (System.currentTimeMillis() - start))
        }
    }.invokeOnCompletion { th -> if (th != null) logger.error("Rate limiter release job completed", th) }

    override fun tick() = semaphore.tryAcquire()

    fun tickBlocking() = semaphore.acquire()
}

class CountingRateLimiter(
    private val rate: Int,
    private val window: Long,
    private val timeUnit: TimeUnit = TimeUnit.SECONDS
): RateLimiter {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(CountingRateLimiter::class.java)
    }

    var internal = RlInternal(System.currentTimeMillis(), rate)

    @Synchronized
    override fun tick(): Boolean {
        val now = System.currentTimeMillis()
        if (now - internal.segmentStart >= timeUnit.toMillis(window)) {
            internal = RlInternal(now, rate - 1)
            return true
        } else {
            if (internal.permits > 0) {
                internal.permits--
                return true
            } else {
                return false
            }
        }
    }

    class RlInternal(
        var segmentStart: Long = System.currentTimeMillis(),
        var permits: Int = 0,
    )
}

fun makeRateLimiter(accountName: String, rate: Int, timeUnit: TimeUnit = TimeUnit.SECONDS): io.github.resilience4j.ratelimiter.RateLimiter {
    val config = RateLimiterConfig.custom()
        .limitRefreshPeriod(if (timeUnit == TimeUnit.SECONDS) Duration.ofSeconds(1) else Duration.ofMinutes(1))
        .limitForPeriod(rate)
        .timeoutDuration(Duration.ofMillis(5))
        .build()

    val rateLimiterRegistry = RateLimiterRegistry.of(config)

    return rateLimiterRegistry.rateLimiter("rateLimiter:${accountName}")
}