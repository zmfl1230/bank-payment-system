package com.bank.payment.payment.repository

import com.bank.payment.payment.domain.Payment
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface PaymentRepository : JpaRepository<Payment, Long> {
    fun findByPaymentId(paymentId: String): Payment?

    fun findByIdempotencyKey(idempotencyKey: String): Payment?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    fun findByIdForUpdate(id: Long): Payment?

    fun findByUserId(userId: String): List<Payment>
}
