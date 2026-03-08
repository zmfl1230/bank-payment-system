package com.bank.payment.bank.repository

import com.bank.payment.bank.domain.Ledger
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface LedgerRepository : JpaRepository<Ledger, Long> {
    fun findByTransactionId(transactionId: String): List<Ledger>

    fun findByIdempotencyKey(idempotencyKey: String): Ledger?

    fun findByAccountIdOrderByCreatedAtDesc(accountId: Long): List<Ledger>

    fun findByAccountIdAndCreatedAtBetween(
        accountId: Long,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
    ): List<Ledger>

    fun findByReferenceTypeAndReferenceId(
        referenceType: String,
        referenceId: String,
    ): List<Ledger>
}
