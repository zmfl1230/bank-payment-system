package com.bank.payment.bank.controller

import com.bank.payment.bank.dto.*
import com.bank.payment.bank.service.TransactionService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/accounts/{accountId}")
class TransactionController(
    private val transactionService: TransactionService,
) {
    @PostMapping("/deposit")
    fun deposit(
        @PathVariable accountId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: DepositRequest,
    ): TransactionResponse {
        val transaction =
            transactionService.deposit(
                accountId = accountId,
                amount = request.amount,
                idempotencyKey = idempotencyKey,
                description = request.description,
            )
        return TransactionResponse.from(transaction)
    }

    @PostMapping("/withdraw")
    fun withdraw(
        @PathVariable accountId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: WithdrawRequest,
    ): TransactionResponse {
        val transaction =
            transactionService.withdraw(
                accountId = accountId,
                amount = request.amount,
                idempotencyKey = idempotencyKey,
                referenceType = request.referenceType,
                referenceId = request.referenceId,
                description = request.description,
            )
        return TransactionResponse.from(transaction)
    }

    @GetMapping("/transactions")
    fun getTransactionHistory(
        @PathVariable accountId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): TransactionHistoryResponse {
        val transactionPage = transactionService.getTransactionHistory(accountId, page, size)

        return TransactionHistoryResponse(
            transactions = transactionPage.content.map { TransactionResponse.from(it) },
            page = transactionPage.number,
            size = transactionPage.size,
            totalElements = transactionPage.totalElements,
            totalPages = transactionPage.totalPages,
        )
    }
}
