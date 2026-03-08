package com.bank.payment.bank.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "account")
data class Account(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    @Column(nullable = false, unique = true, length = 20)
    val accountNumber: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val accountType: AccountType,
    @Column(nullable = false, precision = 19, scale = 2)
    var balance: BigDecimal = BigDecimal.ZERO,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AccountStatus = AccountStatus.ACTIVE,
    @Column(nullable = false, length = 3)
    val currency: String = "KRW",
    @Version
    var version: Long = 0,
    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

enum class AccountType {
    CHECKING,
    SAVINGS,
}

enum class AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED,
}
