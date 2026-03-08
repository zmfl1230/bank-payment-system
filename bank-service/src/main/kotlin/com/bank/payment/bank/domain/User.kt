package com.bank.payment.bank.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(nullable = false, unique = true, length = 50)
    val username: String,
    @Column(nullable = false, unique = true, length = 100)
    val email: String,
    @Column(length = 20)
    val phone: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: UserStatus = UserStatus.ACTIVE,
    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

enum class UserStatus {
    ACTIVE,
    INACTIVE,
    SUSPENDED,
}
