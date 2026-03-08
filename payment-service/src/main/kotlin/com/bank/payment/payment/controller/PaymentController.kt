package com.bank.payment.payment.controller

import com.bank.payment.payment.dto.CancelPaymentRequest
import com.bank.payment.payment.dto.CreatePaymentRequest
import com.bank.payment.payment.dto.PaymentResponse
import com.bank.payment.payment.service.PaymentService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/payments")
class PaymentController(
    private val paymentService: PaymentService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createPayment(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: CreatePaymentRequest,
    ): PaymentResponse {
        val payment =
            paymentService.approvePayment(
                userId = request.userId,
                accountId = request.accountId,
                amount = request.amount,
                idempotencyKey = idempotencyKey,
                merchantId = request.merchantId,
                orderId = request.orderId,
                description = request.description,
            )
        return PaymentResponse.from(payment)
    }

    @PostMapping("/{paymentId}/cancel")
    fun cancelPayment(
        @PathVariable paymentId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: CancelPaymentRequest,
    ): PaymentResponse {
        val payment =
            paymentService.cancelPayment(
                paymentId = paymentId,
                amount = request.amount,
                idempotencyKey = idempotencyKey,
                reason = request.reason,
            )
        return PaymentResponse.from(payment)
    }

    @GetMapping("/{paymentId}")
    fun getPayment(
        @PathVariable paymentId: String,
    ): PaymentResponse {
        val payment = paymentService.getPayment(paymentId)
        return PaymentResponse.from(payment)
    }
}
