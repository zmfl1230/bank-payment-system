package com.bank.payment.bank.service

import com.bank.payment.bank.domain.Account
import com.bank.payment.bank.domain.AccountType
import com.bank.payment.bank.repository.AccountRepository
import com.bank.payment.bank.repository.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.random.Random

@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val userRepository: UserRepository,
) {
    companion object {
        private const val MAX_RETRIES = 3
    }

    @Transactional
    fun createAccount(
        userId: Long,
        accountType: AccountType,
    ): Account {
        val user =
            userRepository.findById(userId)
                .orElseThrow { IllegalArgumentException("User not found: $userId") }

        // Race condition 방지: unique constraint violation 발생 시 재시도
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                val accountNumber = generateAccountNumber()
                val account =
                    Account(
                        user = user,
                        accountNumber = accountNumber,
                        accountType = accountType,
                    )
                return accountRepository.save(account)
            } catch (e: DataIntegrityViolationException) {
                // Unique constraint violation (동시 생성으로 인한 중복)
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    // 재시도 전 짧은 대기 (충돌 완화)
                    Thread.sleep(10L * (attempt + 1))
                }
            }
        }

        throw IllegalStateException("Failed to create account after $MAX_RETRIES retries", lastException)
    }

    @Transactional(readOnly = true)
    fun getBalance(accountId: Long): BigDecimal {
        val account =
            accountRepository.findById(accountId)
                .orElseThrow { IllegalArgumentException("Account not found: $accountId") }

        return account.balance
    }

    @Transactional(readOnly = true)
    fun getAccount(accountId: Long): Account {
        val account = accountRepository.findById(accountId)
            .orElseThrow { IllegalArgumentException("Account not found: $accountId") }

        // Lazy loading 방지: 트랜잭션 내에서 user 엔티티 초기화
        account.user.id

        return account
    }

    private fun generateAccountNumber(): String {
        // DB unique constraint에 의존 (race condition 방지)
        // 중복 시 DataIntegrityViolationException 발생 → 상위에서 재시도
        return Random.nextLong(1_000_000_000L, 9_999_999_999L).toString()
    }
}
