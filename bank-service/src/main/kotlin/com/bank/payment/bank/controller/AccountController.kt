package com.bank.payment.bank.controller

import com.bank.payment.bank.dto.AccountResponse
import com.bank.payment.bank.dto.BalanceResponse
import com.bank.payment.bank.dto.CreateAccountRequest
import com.bank.payment.bank.service.AccountService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/accounts")
class AccountController(
    private val accountService: AccountService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(
        @RequestBody request: CreateAccountRequest,
    ): AccountResponse {
        val account = accountService.createAccount(request.userId, request.accountType)
        return AccountResponse.from(account)
    }

    @GetMapping("/{accountId}")
    fun getAccount(
        @PathVariable accountId: Long,
    ): AccountResponse {
        val account = accountService.getAccount(accountId)
        return AccountResponse.from(account)
    }

    @GetMapping("/{accountId}/balance")
    fun getBalance(
        @PathVariable accountId: Long,
    ): BalanceResponse {
        val account = accountService.getAccount(accountId)
        return BalanceResponse(
            accountId = account.id,
            balance = account.balance,
            currency = account.currency,
            status = account.status,
            lastUpdated = account.updatedAt,
        )
    }
}
