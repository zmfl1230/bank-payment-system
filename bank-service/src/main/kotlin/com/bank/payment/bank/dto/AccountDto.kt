package com.bank.payment.bank.dto

import com.bank.payment.bank.domain.Account
import com.bank.payment.bank.domain.AccountStatus
import com.bank.payment.bank.domain.AccountType
import java.math.BigDecimal
import java.time.LocalDateTime

data class CreateAccountRequest(
    val userId: Long,
    val accountType: AccountType,
)

data class AccountResponse(
    val id: Long,
    val accountNumber: String,
    val userId: Long,
    val accountType: AccountType,
    val balance: BigDecimal,
    val currency: String,
    val status: AccountStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(account: Account) =
            AccountResponse(
                id = account.id,
                accountNumber = account.accountNumber,
                userId = account.user.id,
                accountType = account.accountType,
                balance = account.balance,
                currency = account.currency,
                status = account.status,
                createdAt = account.createdAt,
                updatedAt = account.updatedAt,
            )
    }
}

data class BalanceResponse(
    val accountId: Long,
    val balance: BigDecimal,
    val currency: String,
    val status: AccountStatus,
    val lastUpdated: LocalDateTime,
)
