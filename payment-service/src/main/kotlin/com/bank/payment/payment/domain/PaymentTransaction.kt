package com.bank.payment.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(
    name = "payment_transaction",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["idempotency_key"])
    ],
    indexes = [
        Index(name = "idx_payment_txn_payment_id", columnList = "payment_id"),
        Index(name = "idx_payment_txn_bank_txn_id", columnList = "bank_transaction_id"),
        Index(name = "idx_payment_txn_created_at", columnList = "created_at"),
        Index(name = "idx_payment_txn_status_date", columnList = "status,created_at")
    ],
)
data class PaymentTransaction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    val payment: Payment,
    @Column(nullable = false, unique = true, length = 50)
    val transactionId: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val transactionType: PaymentTransactionType,
    @Column(nullable = false, precision = 19, scale = 2)
    val amount: BigDecimal,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PaymentTransactionStatus = PaymentTransactionStatus.PENDING,
    @Column(length = 50)
    var bankTransactionId: String? = null,
    @Column(nullable = false, unique = true, length = 100)
    val idempotencyKey: String,
    @Column(length = 500)
    val reason: String? = null,
    @Column(nullable = false)
    var retryCount: Int = 0,
    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    var completedAt: LocalDateTime? = null,
)

enum class PaymentTransactionType {
    APPROVAL,
    CANCEL,
}

enum class PaymentTransactionStatus {
    PENDING,
    COMPLETED,
    FAILED,
}
