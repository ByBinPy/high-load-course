package ru.quipy.payments.logic

import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.logic.PaymentExternalSystemAdapterImpl.Companion.logger
import ru.quipy.payments.logic.PaymentExternalSystemAdapterImpl.Companion.mapper
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.pow


class PaymentCallback(val semaphore: Semaphore, val accountName: String, val retryCount: Int, val paymentId: UUID, val transactionId: UUID, val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>, val client: OkHttpClient, val request: Request, val timer: Timer, val deadline: Long, val timeBeforeCall: Long) : Callback {
    private val remaining: Long = 10_000L //ms
    override fun onFailure(call: Call, e: java.io.IOException) {

        logger.debug("fail in callback for payment: {}, retry count: {}, deadline: {}, in time: {}", paymentId, retryCount, deadline, now(),  e)
        when (e) {
            is SocketTimeoutException -> {
                logger.warn("[$accountName] attempt ${retryCount + 1} timeout: $paymentId", e)
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "socket timeout")
                }
            }

            is InterruptedIOException -> {
                logger.warn("[$accountName] interrupted: $paymentId", e)

                // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "interrupted IO")
                }
            }

            else -> {
                logger.warn("[$accountName] io error: $paymentId", e)
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "io exception")
                }
            }
        }

        if (retryCount + 1 >= 3) {
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, "Max attempts reached")
            }
            semaphore.release()
            return
        }

        val backoff = ((2.0.pow(retryCount.toDouble()) * 25).toLong() + kotlin.random.Random.nextLong(10))
        val capped = backoff.coerceAtMost(deadline - now() - 5)
        if (capped <= 0) {
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, "Deadline expired")
            }
            semaphore.release()
            return
        }
        val nCall = client.newCall(request)
        nCall.enqueue(PaymentCallback(semaphore, accountName, retryCount + 1, paymentId, transactionId, paymentESService, client, request, timer, deadline, now()))
        timer.record(now() - timeBeforeCall, TimeUnit.MILLISECONDS)
    }

    override fun onResponse(call: Call, response: Response) {
        try {
            logger.warn("Free space in semaphore: {}", semaphore.availablePermits)
            logger.info(
                "success in callback for payment: {}, retry count: {}, deadline: {}, in time: {}",
                paymentId,
                retryCount,
                deadline,
                now()
            )
            val rawBody = response.body?.string()
            val parsed = try {
                mapper.readValue(rawBody, ExternalSysResponse::class.java)
            } catch (ex: Exception) {
                ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, ex.message)
            }

            paymentESService.update(paymentId) {
                it.logProcessing(parsed.result, now(), transactionId, parsed.message)
            }
        } finally {
            semaphore.release()
            timer.record(now() - timeBeforeCall, TimeUnit.MILLISECONDS)
        }
    }
}