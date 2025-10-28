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
    private val maxWait: Int = 1_000
) {
    companion object {
        val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    @ExceptionHandler(TooManyRequestsException::class)
    fun handleTooManyRequests(): ResponseEntity<Int> {
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", maxWait.toString())
            .build()
    }
}
