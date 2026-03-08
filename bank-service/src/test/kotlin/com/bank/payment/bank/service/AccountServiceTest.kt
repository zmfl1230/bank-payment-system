package com.bank.payment.bank.service

import com.bank.payment.bank.domain.Account
import com.bank.payment.bank.domain.AccountType
import com.bank.payment.bank.domain.User
import com.bank.payment.bank.domain.UserStatus
import com.bank.payment.bank.repository.AccountRepository
import com.bank.payment.bank.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.Optional

class AccountServiceTest {
    private val accountRepository = mockk<AccountRepository>()
    private val userRepository = mockk<UserRepository>()
    private val accountService = AccountService(accountRepository, userRepository)

    @Test
    fun `test create account success`() {
        // Arrange
        val userId = 1L
        val user =
            User(
                id = userId,
                username = "testuser",
                email = "test@example.com",
                status = UserStatus.ACTIVE,
            )

        every { userRepository.findById(userId) } returns Optional.of(user)
        every { accountRepository.existsByAccountNumber(any()) } returns false
        every { accountRepository.save(any<Account>()) } answers { firstArg() }

        // Act
        val account = accountService.createAccount(userId, AccountType.CHECKING)

        // Assert
        assertThat(account.user).isEqualTo(user)
        assertThat(account.accountType).isEqualTo(AccountType.CHECKING)
        assertThat(account.balance).isEqualTo(BigDecimal.ZERO)
        assertThat(account.accountNumber).hasSize(10)
        verify { accountRepository.save(any<Account>()) }
    }

    @Test
    fun `test create account for nonexistent user fails`() {
        // Arrange
        val userId = 999L
        every { userRepository.findById(userId) } returns Optional.empty()

        // Act & Assert
        val exception =
            assertThrows<IllegalArgumentException> {
                accountService.createAccount(userId, AccountType.CHECKING)
            }

        assertThat(exception.message).contains("User not found")
    }

    @Test
    fun `test create multiple accounts for same user`() {
        // Arrange
        val userId = 1L
        val user =
            User(
                id = userId,
                username = "testuser",
                email = "test@example.com",
                status = UserStatus.ACTIVE,
            )

        every { userRepository.findById(userId) } returns Optional.of(user)
        every { accountRepository.existsByAccountNumber(any()) } returns false
        every { accountRepository.save(any<Account>()) } answers { firstArg() }

        // Act
        val account1 = accountService.createAccount(userId, AccountType.CHECKING)
        val account2 = accountService.createAccount(userId, AccountType.SAVINGS)

        // Assert
        assertThat(account1.accountType).isEqualTo(AccountType.CHECKING)
        assertThat(account2.accountType).isEqualTo(AccountType.SAVINGS)
        assertThat(account1.accountNumber).isNotEqualTo(account2.accountNumber)
        verify(exactly = 2) { accountRepository.save(any<Account>()) }
    }

    @Test
    fun `test get account balance`() {
        // Arrange
        val accountId = 1L
        val user =
            User(
                id = 1L,
                username = "testuser",
                email = "test@example.com",
            )
        val account =
            Account(
                id = accountId,
                user = user,
                accountNumber = "1234567890",
                accountType = AccountType.CHECKING,
                balance = BigDecimal("10000.00"),
            )

        every { accountRepository.findById(accountId) } returns Optional.of(account)

        // Act
        val balance = accountService.getBalance(accountId)

        // Assert
        assertThat(balance).isEqualTo(BigDecimal("10000.00"))
    }

    @Test
    fun `test get balance for non-existent account fails`() {
        // Arrange
        val accountId = 999L
        every { accountRepository.findById(accountId) } returns Optional.empty()

        // Act & Assert
        val exception =
            assertThrows<IllegalArgumentException> {
                accountService.getBalance(accountId)
            }

        assertThat(exception.message).contains("Account not found")
    }
}
