package com.bank.payment.bank.service

import com.bank.payment.bank.domain.Account
import com.bank.payment.bank.domain.AccountType
import com.bank.payment.bank.repository.AccountRepository
import com.bank.payment.bank.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.random.Random

@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun createAccount(
        userId: Long,
        accountType: AccountType,
    ): Account {
        val user =
            userRepository.findById(userId)
                .orElseThrow { IllegalArgumentException("User not found: $userId") }

        val accountNumber = generateAccountNumber()

        val account =
            Account(
                user = user,
                accountNumber = accountNumber,
                accountType = accountType,
            )

        return accountRepository.save(account)
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
        return accountRepository.findById(accountId)
            .orElseThrow { IllegalArgumentException("Account not found: $accountId") }
    }

    private fun generateAccountNumber(): String {
        var accountNumber: String
        do {
            accountNumber = Random.nextLong(1_000_000_000L, 9_999_999_999L).toString()
        } while (accountRepository.existsByAccountNumber(accountNumber))

        return accountNumber
    }
}
