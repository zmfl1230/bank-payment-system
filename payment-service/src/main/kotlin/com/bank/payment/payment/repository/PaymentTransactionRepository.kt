package com.bank.payment.payment.repository

import com.bank.payment.payment.domain.PaymentTransaction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PaymentTransactionRepository : JpaRepository<PaymentTransaction, Long> {
    fun findByTransactionId(transactionId: String): PaymentTransaction?

    fun findByIdempotencyKey(idempotencyKey: String): PaymentTransaction?

    fun findByPaymentId(paymentId: Long): List<PaymentTransaction>

    fun findByBankTransactionId(bankTransactionId: String): PaymentTransaction?
}
