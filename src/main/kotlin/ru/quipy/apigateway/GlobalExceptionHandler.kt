package ru.quipy.apigateway

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.quipy.exceptions.TooManyRequestsException
import kotlin.random.Random

@RestControllerAdvice
class GlobalExceptionHandler(
) {
    companion object {
        val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    @ExceptionHandler(TooManyRequestsException::class)
    fun handleTooManyRequests(): ResponseEntity<String> {
        logger.warn("Too many requests - returning 429")
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", "10")
            .build()
    }
}