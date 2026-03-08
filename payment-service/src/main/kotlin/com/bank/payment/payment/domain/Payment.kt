package com.bank.payment.payment.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(
    name = "payment",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["idempotency_key"])
    ],
    indexes = [
        Index(name = "idx_payment_user_id", columnList = "user_id"),
        Index(name = "idx_payment_account_id", columnList = "account_id"),
        Index(name = "idx_payment_status", columnList = "status"),
        Index(name = "idx_payment_created_at", columnList = "created_at")
    ],
)
data class Payment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(nullable = false, unique = true, length = 50)
    val paymentId: String,
    @Column(nullable = false, length = 100)
    val userId: String,
    @Column(nullable = false)
    val accountId: Long,
    @Column(nullable = false, precision = 19, scale = 2)
    val totalAmount: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 2)
    var approvedAmount: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 2)
    var cancelledAmount: BigDecimal = BigDecimal.ZERO,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: PaymentStatus = PaymentStatus.PENDING,
    @Column(length = 100)
    val merchantId: String? = null,
    @Column(length = 100)
    val orderId: String? = null,
    @Column(nullable = false, unique = true, length = 100)
    val idempotencyKey: String,
    @Column(length = 500)
    val description: String? = null,
    @Version
    var version: Long = 0,
    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    @OneToMany(mappedBy = "payment", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val transactions: MutableList<PaymentTransaction> = mutableListOf(),
)

enum class PaymentStatus {
    PENDING,
    APPROVED,
    PARTIALLY_CANCELLED,
    FULLY_CANCELLED,
    FAILED,
}
