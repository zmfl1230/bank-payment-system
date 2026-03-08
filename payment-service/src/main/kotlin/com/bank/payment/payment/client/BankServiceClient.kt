package com.bank.payment.payment.client

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal

@Component
class BankServiceClient(
    @Value("\${bank.service.url}") private val bankServiceUrl: String,
) {
    private val restClient = RestClient.builder().baseUrl(bankServiceUrl).build()

    fun withdraw(
        accountId: Long,
        amount: BigDecimal,
        idempotencyKey: String,
        referenceType: String,
        referenceId: String,
    ): BankTransactionResponse {
        val request =
            WithdrawRequest(
                amount = amount,
                referenceType = referenceType,
                referenceId = referenceId,
                description = "Payment withdrawal",
            )

        return restClient
            .post()
            .uri("/api/accounts/{accountId}/withdraw", accountId)
            .header("Idempotency-Key", idempotencyKey)
            .body(request)
            .retrieve()
            .body(BankTransactionResponse::class.java)!!
    }

    fun deposit(
        accountId: Long,
        amount: BigDecimal,
        idempotencyKey: String,
        referenceType: String,
        referenceId: String,
    ): BankTransactionResponse {
        val request =
            DepositRequest(
                amount = amount,
                description = "Payment refund",
            )

        return restClient
            .post()
            .uri("/api/accounts/{accountId}/deposit", accountId)
            .header("Idempotency-Key", idempotencyKey)
            .body(request)
            .retrieve()
            .body(BankTransactionResponse::class.java)!!
    }
}

data class WithdrawRequest(
    val amount: BigDecimal,
    val referenceType: String,
    val referenceId: String,
    val description: String,
)

data class DepositRequest(
    val amount: BigDecimal,
    val description: String,
)

data class BankTransactionResponse(
    val transactionId: String,
    val accountId: Long,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
)
