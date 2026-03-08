package com.bank.payment.bank.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(
    name = "ledger",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["idempotency_key"]),
    ],
    indexes = [
        Index(name = "idx_ledger_account_id", columnList = "account_id"),
        Index(name = "idx_ledger_created_at", columnList = "created_at"),
        Index(name = "idx_ledger_reference", columnList = "reference_type,reference_id"),
        Index(name = "idx_ledger_account_date", columnList = "account_id,created_at"),
    ],
)
data class Ledger(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    val account: Account,
    @Column(nullable = false, length = 50)
    val transactionId: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val entryType: LedgerEntryType,
    @Column(nullable = false, precision = 19, scale = 2)
    val amount: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 2)
    val balanceBefore: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 2)
    val balanceAfter: BigDecimal,
    @Column(length = 50)
    val referenceType: String? = null,
    @Column(length = 100)
    val referenceId: String? = null,
    @Column(nullable = false, unique = true, length = 100)
    val idempotencyKey: String,
    @Column(length = 500)
    val memo: String? = null,
    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

enum class LedgerEntryType {
    DEBIT, // 차변 (출금, 감소)
    CREDIT, // 대변 (입금, 증가)
}
