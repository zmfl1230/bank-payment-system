package com.bank.payment.bank.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(
    name = "transaction",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["idempotency_key"]),
    ],
    indexes = [
        Index(name = "idx_transaction_account_id", columnList = "account_id"),
        Index(name = "idx_transaction_created_at", columnList = "created_at"),
        Index(name = "idx_transaction_reference", columnList = "reference_type,reference_id"),
        Index(name = "idx_transaction_account_date", columnList = "account_id,created_at"),
    ],
)
data class Transaction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    val account: Account,
    @Column(nullable = false, unique = true, length = 50)
    val transactionId: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val transactionType: TransactionType,
    @Column(nullable = false, precision = 19, scale = 2)
    val amount: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 2)
    val balanceAfter: BigDecimal,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: TransactionStatus = TransactionStatus.COMPLETED,
    @Column(length = 50)
    val referenceType: String? = null,
    @Column(length = 100)
    val referenceId: String? = null,
    @Column(nullable = false, unique = true, length = 100)
    val idempotencyKey: String,
    @Column(length = 500)
    val description: String? = null,
    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    var completedAt: LocalDateTime? = null,
)

enum class TransactionType {
    DEPOSIT,
    WITHDRAW,
    TRANSFER_IN,
    TRANSFER_OUT,
    PAYMENT,
    REFUND,
}

enum class TransactionStatus {
    PENDING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
