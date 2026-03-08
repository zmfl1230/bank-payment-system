package com.bank.payment.bank.service

import com.bank.payment.bank.domain.*
import com.bank.payment.bank.repository.AccountRepository
import com.bank.payment.bank.repository.LedgerRepository
import com.bank.payment.bank.repository.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

@Service
class TransactionService(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val ledgerRepository: LedgerRepository,
) {
    @Transactional
    fun deposit(
        accountId: Long,
        amount: BigDecimal,
        idempotencyKey: String,
        description: String? = null,
    ): Transaction {
        // Check idempotency
        val existingTransaction = transactionRepository.findByIdempotencyKey(idempotencyKey)
        if (existingTransaction != null) {
            return existingTransaction
        }

        // Validate amount
        require(amount > BigDecimal.ZERO) { "Amount must be positive" }

        // Lock account and get current balance
        val account =
            accountRepository.findByIdForUpdate(accountId)
                ?: throw IllegalArgumentException("Account not found: $accountId")

        // Validate account status
        require(account.status == AccountStatus.ACTIVE) { "Account is not active" }

        val balanceBefore = account.balance
        val balanceAfter = balanceBefore + amount

        // Update account balance
        account.balance = balanceAfter
        account.updatedAt = LocalDateTime.now()
        accountRepository.save(account)

        // Create transaction record
        val transaction =
            Transaction(
                account = account,
                transactionId = generateTransactionId(),
                transactionType = TransactionType.DEPOSIT,
                amount = amount,
                balanceAfter = balanceAfter,
                status = TransactionStatus.COMPLETED,
                idempotencyKey = idempotencyKey,
                description = description,
                completedAt = LocalDateTime.now(),
            )
        transactionRepository.save(transaction)

        // Create ledger entry (CREDIT for deposit)
        val ledger =
            Ledger(
                account = account,
                transactionId = transaction.transactionId,
                entryType = LedgerEntryType.CREDIT,
                amount = amount,
                balanceBefore = balanceBefore,
                balanceAfter = balanceAfter,
                idempotencyKey = "$idempotencyKey-ledger",
                memo = description,
            )
        ledgerRepository.save(ledger)

        return transaction
    }

    @Transactional
    fun withdraw(
        accountId: Long,
        amount: BigDecimal,
        idempotencyKey: String,
        referenceType: String? = null,
        referenceId: String? = null,
        description: String? = null,
    ): Transaction {
        // Check idempotency
        val existingTransaction = transactionRepository.findByIdempotencyKey(idempotencyKey)
        if (existingTransaction != null) {
            return existingTransaction
        }

        // Validate amount
        require(amount > BigDecimal.ZERO) { "Amount must be positive" }

        // Pessimistic lock on account
        val account =
            accountRepository.findByIdForUpdate(accountId)
                ?: throw IllegalArgumentException("Account not found: $accountId")

        // Validate account status
        check(account.status == AccountStatus.ACTIVE) { "Account is not active" }

        // Check sufficient balance
        val balanceBefore = account.balance
        check(balanceBefore >= amount) {
            "Insufficient balance: current=$balanceBefore, requested=$amount"
        }

        val balanceAfter = balanceBefore - amount

        // Update account balance
        account.balance = balanceAfter
        account.updatedAt = LocalDateTime.now()
        accountRepository.save(account)

        // Create transaction record
        val transaction =
            Transaction(
                account = account,
                transactionId = generateTransactionId(),
                transactionType = TransactionType.WITHDRAW,
                amount = amount,
                balanceAfter = balanceAfter,
                status = TransactionStatus.COMPLETED,
                referenceType = referenceType,
                referenceId = referenceId,
                idempotencyKey = idempotencyKey,
                description = description,
                completedAt = LocalDateTime.now(),
            )
        transactionRepository.save(transaction)

        // Create ledger entry (DEBIT for withdrawal)
        val ledger =
            Ledger(
                account = account,
                transactionId = transaction.transactionId,
                entryType = LedgerEntryType.DEBIT,
                amount = amount,
                balanceBefore = balanceBefore,
                balanceAfter = balanceAfter,
                referenceType = referenceType,
                referenceId = referenceId,
                idempotencyKey = "$idempotencyKey-ledger",
                memo = description,
            )
        ledgerRepository.save(ledger)

        return transaction
    }

    @Transactional(readOnly = true)
    fun getTransactionHistory(
        accountId: Long,
        page: Int = 0,
        size: Int = 20,
    ): org.springframework.data.domain.Page<Transaction> {
        val pageable =
            org.springframework.data.domain.PageRequest.of(
                page,
                size,
                org.springframework.data.domain.Sort.by("createdAt").descending(),
            )
        return transactionRepository.findByAccountId(accountId, pageable)
    }

    private fun generateTransactionId(): String {
        return "TXN-${UUID.randomUUID().toString().substring(0, 8).uppercase()}"
    }
}
