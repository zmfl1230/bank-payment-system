package com.bank.payment.bank.service

import com.bank.payment.bank.domain.Account
import com.bank.payment.bank.domain.AccountStatus
import com.bank.payment.bank.domain.AccountType
import com.bank.payment.bank.domain.Transaction
import com.bank.payment.bank.domain.TransactionStatus
import com.bank.payment.bank.domain.TransactionType
import com.bank.payment.bank.domain.User
import com.bank.payment.bank.domain.UserStatus
import com.bank.payment.bank.repository.AccountRepository
import com.bank.payment.bank.repository.LedgerRepository
import com.bank.payment.bank.repository.TransactionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class TransactionServiceTest {
    private val accountRepository = mockk<AccountRepository>()
    private val transactionRepository = mockk<TransactionRepository>()
    private val ledgerRepository = mockk<LedgerRepository>()
    private val transactionService =
        TransactionService(
            accountRepository,
            transactionRepository,
            ledgerRepository,
        )

    @Test
    fun `test deposit success`() {
        // Arrange
        val accountId = 1L
        val user = createTestUser()
        val account = createTestAccount(accountId, user, BigDecimal.ZERO)

        every { accountRepository.findByIdForUpdate(accountId) } returns account
        every { transactionRepository.findByIdempotencyKey(any()) } returns null
        every { transactionRepository.save(any()) } answers { firstArg() }
        every { ledgerRepository.save(any()) } answers { firstArg() }
        every { accountRepository.save(any()) } answers { firstArg() }

        // Act
        val transaction =
            transactionService.deposit(
                accountId = accountId,
                amount = BigDecimal("10000"),
                idempotencyKey = "test-key-1",
                description = "Test deposit",
            )

        // Assert
        assertThat(transaction.transactionType).isEqualTo(TransactionType.DEPOSIT)
        assertThat(transaction.amount).isEqualTo(BigDecimal("10000"))
        assertThat(transaction.balanceAfter).isEqualTo(BigDecimal("10000"))
        assertThat(transaction.status).isEqualTo(TransactionStatus.COMPLETED)
        assertThat(account.balance).isEqualTo(BigDecimal("10000"))
        verify { transactionRepository.save(any()) }
        verify { ledgerRepository.save(any()) }
    }

    @Test
    fun `test deposit with same idempotency key returns same result`() {
        // Arrange
        val accountId = 1L
        val user = createTestUser()
        val account = createTestAccount(accountId, user)

        val existingTransaction =
            Transaction(
                id = 1L,
                account = account,
                transactionId = "TXN-001",
                transactionType = TransactionType.DEPOSIT,
                amount = BigDecimal("10000"),
                balanceAfter = BigDecimal("10000"),
                idempotencyKey = "duplicate-key",
                status = TransactionStatus.COMPLETED,
            )

        every { transactionRepository.findByIdempotencyKey("duplicate-key") } returns existingTransaction

        // Act
        val transaction =
            transactionService.deposit(
                accountId = accountId,
                amount = BigDecimal("10000"),
                idempotencyKey = "duplicate-key",
            )

        // Assert
        assertThat(transaction).isEqualTo(existingTransaction)
        verify(exactly = 0) { accountRepository.findByIdForUpdate(any()) }
    }

    @Test
    fun `test deposit zero amount fails`() {
        // Arrange
        val accountId = 1L
        every { transactionRepository.findByIdempotencyKey(any()) } returns null

        // Act & Assert
        val exception =
            assertThrows<IllegalArgumentException> {
                transactionService.deposit(
                    accountId = accountId,
                    amount = BigDecimal.ZERO,
                    idempotencyKey = "test-key",
                )
            }

        assertThat(exception.message).contains("Amount must be positive")
    }

    @Test
    fun `test deposit negative amount fails`() {
        // Arrange
        val accountId = 1L
        every { transactionRepository.findByIdempotencyKey(any()) } returns null

        // Act & Assert
        val exception =
            assertThrows<IllegalArgumentException> {
                transactionService.deposit(
                    accountId = accountId,
                    amount = BigDecimal("-1000"),
                    idempotencyKey = "test-key",
                )
            }

        assertThat(exception.message).contains("Amount must be positive")
    }

    @Test
    fun `test withdraw success`() {
        // Arrange
        val accountId = 1L
        val user = createTestUser()
        val account = createTestAccount(accountId, user, BigDecimal("10000"))

        every { accountRepository.findByIdForUpdate(accountId) } returns account
        every { transactionRepository.findByIdempotencyKey(any()) } returns null
        every { transactionRepository.save(any()) } answers { firstArg() }
        every { ledgerRepository.save(any()) } answers { firstArg() }
        every { accountRepository.save(any()) } answers { firstArg() }

        // Act
        val transaction =
            transactionService.withdraw(
                accountId = accountId,
                amount = BigDecimal("3000"),
                idempotencyKey = "test-withdraw-key",
                referenceType = "PAYMENT",
                referenceId = "PAY-001",
            )

        // Assert
        assertThat(transaction.transactionType).isEqualTo(TransactionType.WITHDRAW)
        assertThat(transaction.amount).isEqualTo(BigDecimal("3000"))
        assertThat(transaction.balanceAfter).isEqualTo(BigDecimal("7000"))
        assertThat(transaction.referenceType).isEqualTo("PAYMENT")
        assertThat(transaction.referenceId).isEqualTo("PAY-001")
        assertThat(account.balance).isEqualTo(BigDecimal("7000"))
    }

    @Test
    fun `test withdraw insufficient balance fails`() {
        // Arrange
        val accountId = 1L
        val user = createTestUser()
        val account = createTestAccount(accountId, user, BigDecimal("5000"))

        every { accountRepository.findByIdForUpdate(accountId) } returns account
        every { transactionRepository.findByIdempotencyKey(any()) } returns null

        // Act & Assert
        val exception =
            assertThrows<IllegalStateException> {
                transactionService.withdraw(
                    accountId = accountId,
                    amount = BigDecimal("10000"),
                    idempotencyKey = "test-key",
                )
            }

        assertThat(exception.message).contains("Insufficient balance")
        assertThat(account.balance).isEqualTo(BigDecimal("5000")) // Balance unchanged
    }

    @Test
    fun `test withdraw from frozen account fails`() {
        // Arrange
        val accountId = 1L
        val user = createTestUser()
        val account = createTestAccount(accountId, user, BigDecimal("10000"))
        account.status = AccountStatus.FROZEN

        every { accountRepository.findByIdForUpdate(accountId) } returns account
        every { transactionRepository.findByIdempotencyKey(any()) } returns null

        // Act & Assert
        val exception =
            assertThrows<IllegalStateException> {
                transactionService.withdraw(
                    accountId = accountId,
                    amount = BigDecimal("3000"),
                    idempotencyKey = "test-key",
                )
            }

        assertThat(exception.message).contains("Account is not active")
    }

    @Test
    fun `test withdraw exact balance succeeds`() {
        // Arrange
        val accountId = 1L
        val user = createTestUser()
        val account = createTestAccount(accountId, user, BigDecimal("10000"))

        every { accountRepository.findByIdForUpdate(accountId) } returns account
        every { transactionRepository.findByIdempotencyKey(any()) } returns null
        every { transactionRepository.save(any()) } answers { firstArg() }
        every { ledgerRepository.save(any()) } answers { firstArg() }
        every { accountRepository.save(any()) } answers { firstArg() }

        // Act
        val transaction =
            transactionService.withdraw(
                accountId = accountId,
                amount = BigDecimal("10000"),
                idempotencyKey = "test-key",
            )

        // Assert
        assertThat(transaction.balanceAfter).isEqualTo(BigDecimal.ZERO)
        assertThat(account.balance).isEqualTo(BigDecimal.ZERO)
    }

    private fun createTestUser() =
        User(
            id = 1L,
            username = "testuser",
            email = "test@example.com",
            status = UserStatus.ACTIVE,
        )

    private fun createTestAccount(
        id: Long,
        user: User,
        balance: BigDecimal = BigDecimal.ZERO,
    ) = Account(
        id = id,
        user = user,
        accountNumber = "1234567890",
        accountType = AccountType.CHECKING,
        balance = balance,
    )
}
