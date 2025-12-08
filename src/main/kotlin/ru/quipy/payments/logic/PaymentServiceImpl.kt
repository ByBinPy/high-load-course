package ru.quipy.payments.logic

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*


@Service
class PaymentSystemImpl(
    private val paymentAccounts: List<PaymentExternalSystemAdapter>
) : PaymentService {
    companion object {
        val logger = LoggerFactory.getLogger(PaymentSystemImpl::class.java)
    }

    override fun submitPaymentRequest(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        for (account in paymentAccounts) {
            account.performPaymentAsync(paymentId, amount, paymentStartedAt, deadline)
        }
    }
}