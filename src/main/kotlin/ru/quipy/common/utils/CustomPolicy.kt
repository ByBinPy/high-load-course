package ru.quipy.common.utils

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class CallerBlockingRejectedExecutionHandler(
    private val maxWait: Duration = Duration.ofMinutes(30),
) : RejectedExecutionHandler {
    companion object {
        val logger = LoggerFactory.getLogger(CallerBlockingRejectedExecutionHandler::class.java)
    }

    override fun rejectedExecution(r: Runnable, executor: ThreadPoolExecutor) {
        if (!executor.isShutdown) {
            try {
                val queue = executor.queue
                val offer = queue.offer(r, maxWait.toMillis(), TimeUnit.MILLISECONDS)
                if (!offer) {
                    throw RejectedExecutionException("Max wait time expired to queue task")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RejectedExecutionException("Interrupted", e)
            }
        } else {
            throw RejectedExecutionException("Executor has been shut down")
        }
    }
}