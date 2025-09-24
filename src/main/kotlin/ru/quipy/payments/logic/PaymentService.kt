package ru.quipy.payments.logic

import java.time.Duration
import java.util.*

interface PaymentService {
    /**
     * Submit payment request to some external service.
     */
    fun submitPaymentRequest(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long)
}

/**
 * Adapter for external payment system. Represents the account in the external system.
 *
 * !!! You can extend the interface with additional methods if needed. !!!

 */
interface PaymentExternalSystemAdapter {
    fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long)

    fun name(): String

    fun price(): Int

    fun isEnabled(): Boolean
}

/**
 * Describes properties of payment-provider accounts.
 */
data class PaymentAccountProperties(
    val serviceName: String,
    val accountName: String,
    val parallelRequests: Int, // 30
    val rateLimitPerSec: Int,  // 10
    val price: Int,            // 30
    val averageProcessingTime: Duration = Duration.ofSeconds(11),
    val enabled: Boolean,
)

/*
#- parallelRequests=5 - означает, что провайдер разрешает вам в любой момент времени иметь не более 5 одновременных запросов от вас к нему для этого аккаунта
#- rateLimitPerSec=5 - означает, что провайдер разрешает вам каждую секунду отправлять к нему не более 5 запросов по этому аккаунту
#- price=30 - означает, что провайдер оплаты будет взымать за каждый успешный или неуспешный вызов 30 денежных единиц с вашего магазина.
#- averageProcessingTime=PTO.05S - провайдер оплаты сообщает вам, что в среднем время обработки одного запроса по этому аккаунту будет составлять около 50ms.

 */

/**
 * Describes response from external service.
 */
class ExternalSysResponse(
    val transactionId: String,
    val paymentId: String,
    val result: Boolean,
    val message: String? = null,
)