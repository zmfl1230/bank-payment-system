package com.bank.payment.bank.repository

import com.bank.payment.bank.domain.Transaction
import com.bank.payment.bank.domain.TransactionType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface TransactionRepository : JpaRepository<Transaction, Long> {
    fun findByTransactionId(transactionId: String): Transaction?

    fun findByIdempotencyKey(idempotencyKey: String): Transaction?

    fun findByAccountId(
        accountId: Long,
        pageable: Pageable,
    ): Page<Transaction>

    fun findByAccountIdAndTransactionType(
        accountId: Long,
        transactionType: TransactionType,
        pageable: Pageable,
    ): Page<Transaction>

    fun findByAccountIdAndCreatedAtBetween(
        accountId: Long,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        pageable: Pageable,
    ): Page<Transaction>

    fun findByReferenceTypeAndReferenceId(
        referenceType: String,
        referenceId: String,
    ): List<Transaction>
}
