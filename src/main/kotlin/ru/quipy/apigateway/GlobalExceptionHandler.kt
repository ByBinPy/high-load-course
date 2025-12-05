package ru.quipy.apigateway

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.quipy.core.EventSourcingService
import ru.quipy.exceptions.DeadlineExceededException
import ru.quipy.exceptions.TooManyRequestsException
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.logic.PaymentAggregateState
import ru.quipy.payments.logic.logProcessing
import ru.quipy.payments.logic.now
import java.util.UUID

@RestControllerAdvice
class GlobalExceptionHandler(
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) {
    companion object {
        val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    @ExceptionHandler(TooManyRequestsException::class)
    fun handleTooManyRequests(ex: TooManyRequestsException): ResponseEntity<String> {
        val wait = ex.retryAfterMillisecond
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", wait.toString())
            .build()
    }

    @ExceptionHandler(DeadlineExceededException::class)
    fun handleUnprocessableEntity(): ResponseEntity<String> {

        return ResponseEntity
            .status(HttpStatus.OK)
            .build()
    }
}