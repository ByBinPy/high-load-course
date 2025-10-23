package ru.quipy.payments.dto

import kotlinx.coroutines.Runnable
import java.util.UUID

data class Transaction(val orderId: UUID, val amount: Int, val paymentId: UUID, val deadline: Long, val task : Runnable) : Runnable {
    override fun run() = task.run()
}