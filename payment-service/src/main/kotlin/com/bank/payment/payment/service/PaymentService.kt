package com.bank.payment.payment.service

import com.bank.payment.payment.client.BankServiceClient
import com.bank.payment.payment.domain.Payment
import com.bank.payment.payment.domain.PaymentStatus
import com.bank.payment.payment.domain.PaymentTransaction
import com.bank.payment.payment.domain.PaymentTransactionStatus
import com.bank.payment.payment.domain.PaymentTransactionType
import com.bank.payment.payment.repository.PaymentRepository
import com.bank.payment.payment.repository.PaymentTransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val paymentTransactionRepository: PaymentTransactionRepository,
    private val bankServiceClient: BankServiceClient,
) {
    @Transactional
    fun approvePayment(
        userId: String,
        accountId: Long,
        amount: BigDecimal,
        idempotencyKey: String,
        merchantId: String? = null,
        orderId: String? = null,
        description: String? = null,
    ): Payment {
        // Check idempotency
        val existingPayment = paymentRepository.findByIdempotencyKey(idempotencyKey)
        if (existingPayment != null) {
            return existingPayment
        }

        // Validate amount
        require(amount > BigDecimal.ZERO) { "Amount must be positive" }

        // Create payment
        val payment =
            Payment(
                paymentId = generatePaymentId(),
                userId = userId,
                accountId = accountId,
                totalAmount = amount,
                approvedAmount = amount,
                cancelledAmount = BigDecimal.ZERO,
                status = PaymentStatus.PENDING,
                merchantId = merchantId,
                orderId = orderId,
                idempotencyKey = idempotencyKey,
                description = description,
            )
        paymentRepository.save(payment)

        // Create approval transaction
        val transaction =
            PaymentTransaction(
                payment = payment,
                transactionId = generateTransactionId(),
                transactionType = PaymentTransactionType.APPROVAL,
                amount = amount,
                status = PaymentTransactionStatus.PENDING,
                idempotencyKey = "$idempotencyKey-approval",
            )
        paymentTransactionRepository.save(transaction)

        // Call Bank Service to withdraw
        try {
            val bankResponse =
                bankServiceClient.withdraw(
                    accountId = accountId,
                    amount = amount,
                    idempotencyKey = "$idempotencyKey-bank",
                    referenceType = "PAYMENT",
                    referenceId = payment.paymentId,
                )

            // Update transaction with bank transaction ID
            transaction.bankTransactionId = bankResponse.transactionId
            transaction.status = PaymentTransactionStatus.COMPLETED
            transaction.completedAt = LocalDateTime.now()
            paymentTransactionRepository.save(transaction)

            // Update payment status
            payment.status = PaymentStatus.APPROVED
            payment.updatedAt = LocalDateTime.now()
            paymentRepository.save(payment)

            return payment
        } catch (e: Exception) {
            // Mark transaction as failed
            transaction.status = PaymentTransactionStatus.FAILED
            paymentTransactionRepository.save(transaction)

            // Mark payment as failed
            payment.status = PaymentStatus.FAILED
            paymentRepository.save(payment)

            throw IllegalStateException("Payment approval failed: ${e.message}", e)
        }
    }

    @Transactional
    fun cancelPayment(
        paymentId: String,
        amount: BigDecimal?,
        idempotencyKey: String,
        reason: String? = null,
    ): Payment {
        // Check idempotency
        val existingTransaction = paymentTransactionRepository.findByIdempotencyKey(idempotencyKey)
        if (existingTransaction != null) {
            return existingTransaction.payment
        }

        // Lock payment
        val payment =
            paymentRepository.findByPaymentId(paymentId)
                ?: throw IllegalArgumentException("Payment not found: $paymentId")

        // Validate payment status
        check(payment.status == PaymentStatus.APPROVED || payment.status == PaymentStatus.PARTIALLY_CANCELLED) {
            "Payment cannot be cancelled in ${payment.status} status"
        }

        // Determine cancellation amount
        val cancelAmount = amount ?: payment.approvedAmount
        require(cancelAmount > BigDecimal.ZERO) { "Cancel amount must be positive" }
        require(cancelAmount <= payment.approvedAmount) {
            "Cancel amount ($cancelAmount) exceeds approved amount (${payment.approvedAmount})"
        }

        // Create cancellation transaction
        val transaction =
            PaymentTransaction(
                payment = payment,
                transactionId = generateTransactionId(),
                transactionType = PaymentTransactionType.CANCEL,
                amount = cancelAmount,
                status = PaymentTransactionStatus.PENDING,
                idempotencyKey = idempotencyKey,
                reason = reason,
            )
        paymentTransactionRepository.save(transaction)

        // Call Bank Service to deposit (refund)
        try {
            val bankResponse =
                bankServiceClient.deposit(
                    accountId = payment.accountId,
                    amount = cancelAmount,
                    idempotencyKey = "$idempotencyKey-bank",
                    referenceType = "PAYMENT_CANCEL",
                    referenceId = payment.paymentId,
                )

            // Update transaction
            transaction.bankTransactionId = bankResponse.transactionId
            transaction.status = PaymentTransactionStatus.COMPLETED
            transaction.completedAt = LocalDateTime.now()
            paymentTransactionRepository.save(transaction)

            // Update payment
            payment.approvedAmount = payment.approvedAmount - cancelAmount
            payment.cancelledAmount = payment.cancelledAmount + cancelAmount
            payment.status =
                if (payment.approvedAmount == BigDecimal.ZERO) {
                    PaymentStatus.FULLY_CANCELLED
                } else {
                    PaymentStatus.PARTIALLY_CANCELLED
                }
            payment.updatedAt = LocalDateTime.now()
            paymentRepository.save(payment)

            return payment
        } catch (e: Exception) {
            // Mark transaction as failed
            transaction.status = PaymentTransactionStatus.FAILED
            paymentTransactionRepository.save(transaction)

            throw IllegalStateException("Payment cancellation failed: ${e.message}", e)
        }
    }

    @Transactional(readOnly = true)
    fun getPayment(paymentId: String): Payment {
        return paymentRepository.findByPaymentId(paymentId)
            ?: throw IllegalArgumentException("Payment not found: $paymentId")
    }

    private fun generatePaymentId(): String = "PAY-${UUID.randomUUID().toString().substring(0, 8).uppercase()}"

    private fun generateTransactionId(): String = "PTXN-${UUID.randomUUID().toString().substring(0, 8).uppercase()}"
}
