package com.bank.payment.bank.dto

import com.bank.payment.bank.domain.Transaction
import com.bank.payment.bank.domain.TransactionStatus
import com.bank.payment.bank.domain.TransactionType
import java.math.BigDecimal
import java.time.LocalDateTime

data class DepositRequest(
    val amount: BigDecimal,
    val description: String? = null,
)

data class WithdrawRequest(
    val amount: BigDecimal,
    val referenceType: String? = null,
    val referenceId: String? = null,
    val description: String? = null,
)

data class TransactionResponse(
    val transactionId: String,
    val accountId: Long,
    val transactionType: TransactionType,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val status: TransactionStatus,
    val referenceType: String? = null,
    val referenceId: String? = null,
    val description: String? = null,
    val createdAt: LocalDateTime,
    val completedAt: LocalDateTime? = null,
) {
    companion object {
        fun from(transaction: Transaction) =
            TransactionResponse(
                transactionId = transaction.transactionId,
                accountId = transaction.account.id,
                transactionType = transaction.transactionType,
                amount = transaction.amount,
                balanceAfter = transaction.balanceAfter,
                status = transaction.status,
                referenceType = transaction.referenceType,
                referenceId = transaction.referenceId,
                description = transaction.description,
                createdAt = transaction.createdAt,
                completedAt = transaction.completedAt,
            )
    }
}

data class TransactionHistoryResponse(
    val transactions: List<TransactionResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
