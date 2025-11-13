package ru.quipy.apigateway

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.quipy.exceptions.TooManyRequestsRetriableException
import ru.quipy.exceptions.TooLongRequestException
import ru.quipy.exceptions.TooManyRequestsException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RestControllerAdvice
class GlobalExceptionHandler(
) {
    companion object {
        val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
        private var currentRetryAfterSeconds = 1
        private var exp_base = 2;
        private const val MAX_RETRY_AFTER_MS = 256
        private const val RESET_WINDOW_MS = 30000L
    }

    private val rejectedRequestsCount = AtomicInteger(0)
    private val lastRejectionTime = AtomicLong(0)
    @ExceptionHandler(TooLongRequestException::class)
    fun handleTooManyRequests(exception: TooLongRequestException): ResponseEntity<String> {
        return ResponseEntity.status(200).body("your request very long, i am so sorry")
    }
    @ExceptionHandler(TooManyRequestsException::class)
    fun handleTooManyRequestsRetriable(exception: TooManyRequestsException): ResponseEntity<String> {
        logger.warn("to many request")
        val currentTime = System.currentTimeMillis()
        val lastRejection = lastRejectionTime.get()

        if (currentTime - lastRejection > RESET_WINDOW_MS) {
            rejectedRequestsCount.set(0)
            currentRetryAfterSeconds = 1
        }
        lastRejectionTime.set(currentTime)

        if (rejectedRequestsCount.get() < 4 && exception.deadline < System.currentTimeMillis() + 1200) {
            return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", MAX_RETRY_AFTER_MS.coerceAtLeast(currentRetryAfterSeconds).toString())
                .build()
        }

        return ResponseEntity.status(200).build()
    }
}