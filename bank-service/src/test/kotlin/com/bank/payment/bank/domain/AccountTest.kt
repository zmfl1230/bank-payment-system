package com.bank.payment.bank.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AccountTest {
    @Test
    fun `test account creation with valid user`() {
        // Arrange
        val user =
            User(
                id = 1L,
                username = "testuser",
                email = "test@example.com",
                status = UserStatus.ACTIVE,
            )

        // Act
        val account =
            Account(
                user = user,
                accountNumber = "1234567890",
                accountType = AccountType.CHECKING,
            )

        // Assert
        assertThat(account.user).isEqualTo(user)
        assertThat(account.accountNumber).isEqualTo("1234567890")
        assertThat(account.accountType).isEqualTo(AccountType.CHECKING)
    }

    @Test
    fun `test account has zero initial balance`() {
        // Arrange
        val user =
            User(
                id = 1L,
                username = "testuser",
                email = "test@example.com",
            )

        // Act
        val account =
            Account(
                user = user,
                accountNumber = "1234567890",
                accountType = AccountType.CHECKING,
            )

        // Assert
        assertThat(account.balance).isEqualTo(BigDecimal.ZERO)
    }

    @Test
    fun `test account status is ACTIVE by default`() {
        // Arrange
        val user =
            User(
                id = 1L,
                username = "testuser",
                email = "test@example.com",
            )

        // Act
        val account =
            Account(
                user = user,
                accountNumber = "1234567890",
                accountType = AccountType.CHECKING,
            )

        // Assert
        assertThat(account.status).isEqualTo(AccountStatus.ACTIVE)
    }

    @Test
    fun `test account currency is KRW by default`() {
        // Arrange
        val user =
            User(
                id = 1L,
                username = "testuser",
                email = "test@example.com",
            )

        // Act
        val account =
            Account(
                user = user,
                accountNumber = "1234567890",
                accountType = AccountType.CHECKING,
            )

        // Assert
        assertThat(account.currency).isEqualTo("KRW")
    }
}
