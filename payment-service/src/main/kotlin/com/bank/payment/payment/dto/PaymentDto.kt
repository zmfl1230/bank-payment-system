package com.bank.payment.payment.dto

import com.bank.payment.payment.domain.Payment
import com.bank.payment.payment.domain.PaymentStatus
import com.bank.payment.payment.domain.PaymentTransaction
import com.bank.payment.payment.domain.PaymentTransactionStatus
import com.bank.payment.payment.domain.PaymentTransactionType
import java.math.BigDecimal
import java.time.LocalDateTime

data class CreatePaymentRequest(
    val userId: String,
    val accountId: Long,
    val amount: BigDecimal,
    val merchantId: String? = null,
    val orderId: String? = null,
    val description: String? = null,
)

data class CancelPaymentRequest(
    val amount: BigDecimal? = null,
    val reason: String? = null,
)

data class PaymentResponse(
    val paymentId: String,
    val userId: String,
    val accountId: Long,
    val totalAmount: BigDecimal,
    val approvedAmount: BigDecimal,
    val cancelledAmount: BigDecimal,
    val status: PaymentStatus,
    val merchantId: String?,
    val orderId: String?,
    val description: String?,
    val transactions: List<PaymentTransactionResponse>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(payment: Payment): PaymentResponse =
            PaymentResponse(
                paymentId = payment.paymentId,
                userId = payment.userId,
                accountId = payment.accountId,
                totalAmount = payment.totalAmount,
                approvedAmount = payment.approvedAmount,
                cancelledAmount = payment.cancelledAmount,
                status = payment.status,
                merchantId = payment.merchantId,
                orderId = payment.orderId,
                description = payment.description,
                transactions = payment.transactions.map { PaymentTransactionResponse.from(it) },
                createdAt = payment.createdAt,
                updatedAt = payment.updatedAt,
            )
    }
}

data class PaymentTransactionResponse(
    val transactionId: String,
    val transactionType: PaymentTransactionType,
    val amount: BigDecimal,
    val status: PaymentTransactionStatus,
    val bankTransactionId: String?,
    val reason: String?,
    val createdAt: LocalDateTime,
    val completedAt: LocalDateTime?,
) {
    companion object {
        fun from(transaction: PaymentTransaction): PaymentTransactionResponse =
            PaymentTransactionResponse(
                transactionId = transaction.transactionId,
                transactionType = transaction.transactionType,
                amount = transaction.amount,
                status = transaction.status,
                bankTransactionId = transaction.bankTransactionId,
                reason = transaction.reason,
                createdAt = transaction.createdAt,
                completedAt = transaction.completedAt,
            )
    }
}

data class ErrorResponse(
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val status: Int,
    val error: String,
    val message: String,
    val path: String? = null,
)
