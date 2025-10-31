package ru.quipy.apigateway

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.quipy.exceptions.TooManyRequestsException

@RestControllerAdvice
class GlobalExceptionHandler() {
    companion object {
        val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    @ExceptionHandler(TooManyRequestsException::class)
    fun handleTooManyRequests(ex: TooManyRequestsException): ResponseEntity<Int> {

        logger.info("Retry-After: {}", ex.retryAfterMillisecond)

        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", ex.retryAfterMillisecond.toString())
            .build()
    }
}
