package com.bank.payment.payment.integration

import com.bank.payment.payment.client.BankServiceClient
import com.bank.payment.payment.client.BankTransactionResponse
import com.bank.payment.payment.domain.PaymentStatus
import com.bank.payment.payment.dto.CancelPaymentRequest
import com.bank.payment.payment.dto.CreatePaymentRequest
import com.bank.payment.payment.repository.PaymentRepository
import com.bank.payment.payment.repository.PaymentTransactionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Payment Service Integration Tests")
class PaymentServiceIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var paymentRepository: PaymentRepository

    @Autowired
    private lateinit var paymentTransactionRepository: PaymentTransactionRepository

    @MockkBean
    private lateinit var bankServiceClient: BankServiceClient

    @BeforeEach
    fun setup() {
        paymentTransactionRepository.deleteAll()
        paymentRepository.deleteAll()
    }

    @Test
    @DisplayName("E2E: 결제 승인 → 부분 취소 → 부분 취소 → 잔액 확인")
    fun testCompletePaymentFlow() {
        // Mock Bank Service responses
        every {
            bankServiceClient.withdraw(any(), any(), any(), any(), any())
        } returns
            BankTransactionResponse(
                transactionId = "BANK-TXN-001",
                accountId = 1L,
                amount = BigDecimal("10000"),
                balanceAfter = BigDecimal("90000"),
            )

        every {
            bankServiceClient.deposit(any(), any(), any(), any(), any())
        } returns
            BankTransactionResponse(
                transactionId = "BANK-TXN-002",
                accountId = 1L,
                amount = BigDecimal("3000"),
                balanceAfter = BigDecimal("93000"),
            )

        // 1. Create payment (10,000원)
        val createRequest =
            CreatePaymentRequest(
                userId = "user-123",
                accountId = 1L,
                amount = BigDecimal("10000"),
                merchantId = "MERCHANT-001",
                orderId = "ORDER-001",
                description = "Product purchase",
            )

        val createResult =
            mockMvc
                .perform(
                    post("/api/payments")
                        .header("Idempotency-Key", "payment-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.paymentId").exists())
                .andExpect(jsonPath("$.totalAmount").value(10000))
                .andExpect(jsonPath("$.approvedAmount").value(10000))
                .andExpect(jsonPath("$.cancelledAmount").value(0))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn()

        val paymentId = objectMapper.readTree(createResult.response.contentAsString).get("paymentId").asText()

        // 2. First partial cancellation (3,000원)
        val cancelRequest1 =
            CancelPaymentRequest(
                amount = BigDecimal("3000"),
                reason = "Customer request",
            )

        every {
            bankServiceClient.deposit(any(), eq(BigDecimal("3000")), any(), any(), any())
        } returns
            BankTransactionResponse(
                transactionId = "BANK-TXN-CANCEL-001",
                accountId = 1L,
                amount = BigDecimal("3000"),
                balanceAfter = BigDecimal("93000"),
            )

        mockMvc
            .perform(
                post("/api/payments/$paymentId/cancel")
                    .header("Idempotency-Key", "cancel-001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(cancelRequest1)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.approvedAmount").value(7000))
            .andExpect(jsonPath("$.cancelledAmount").value(3000))
            .andExpect(jsonPath("$.status").value("PARTIALLY_CANCELLED"))

        // 3. Second partial cancellation (2,000원)
        val cancelRequest2 =
            CancelPaymentRequest(
                amount = BigDecimal("2000"),
                reason = "Additional refund",
            )

        every {
            bankServiceClient.deposit(any(), eq(BigDecimal("2000")), any(), any(), any())
        } returns
            BankTransactionResponse(
                transactionId = "BANK-TXN-CANCEL-002",
                accountId = 1L,
                amount = BigDecimal("2000"),
                balanceAfter = BigDecimal("95000"),
            )

        mockMvc
            .perform(
                post("/api/payments/$paymentId/cancel")
                    .header("Idempotency-Key", "cancel-002")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(cancelRequest2)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.approvedAmount").value(5000))
            .andExpect(jsonPath("$.cancelledAmount").value(5000))
            .andExpect(jsonPath("$.status").value("PARTIALLY_CANCELLED"))

        // 4. Get payment details
        mockMvc
            .perform(get("/api/payments/$paymentId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalAmount").value(10000))
            .andExpect(jsonPath("$.approvedAmount").value(5000))
            .andExpect(jsonPath("$.cancelledAmount").value(5000))
            .andExpect(jsonPath("$.status").value("PARTIALLY_CANCELLED"))

        // 5. Verify transaction count (1 approval + 2 cancellations)
        val payment = paymentRepository.findByPaymentId(paymentId)!!
        val transactions = paymentTransactionRepository.findByPaymentId(payment.id)
        assertThat(transactions).hasSize(3)
    }

    @Test
    @DisplayName("전체 취소 테스트")
    fun testFullCancellation() {
        every {
            bankServiceClient.withdraw(any(), any(), any(), any(), any())
        } returns
            BankTransactionResponse(
                transactionId = "BANK-TXN-001",
                accountId = 1L,
                amount = BigDecimal("5000"),
                balanceAfter = BigDecimal("95000"),
            )

        every {
            bankServiceClient.deposit(any(), any(), any(), any(), any())
        } returns
            BankTransactionResponse(
                transactionId = "BANK-TXN-CANCEL-001",
                accountId = 1L,
                amount = BigDecimal("5000"),
                balanceAfter = BigDecimal("100000"),
            )

        // Create payment
        val createRequest =
            CreatePaymentRequest(
                userId = "user-123",
                accountId = 1L,
                amount = BigDecimal("5000"),
                description = "Test payment",
            )

        val createResult =
            mockMvc
                .perform(
                    post("/api/payments")
                        .header("Idempotency-Key", "full-cancel-payment-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)),
                ).andReturn()

        val paymentId = objectMapper.readTree(createResult.response.contentAsString).get("paymentId").asText()

        // Full cancellation (amount = null means full cancellation)
        val cancelRequest = CancelPaymentRequest(amount = null, reason = "Full refund")

        mockMvc
            .perform(
                post("/api/payments/$paymentId/cancel")
                    .header("Idempotency-Key", "full-cancel-001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(cancelRequest)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.approvedAmount").value(0))
            .andExpect(jsonPath("$.cancelledAmount").value(5000))
            .andExpect(jsonPath("$.status").value("FULLY_CANCELLED"))
    }

    @Test
    @DisplayName("멱등성 테스트: 동일 Idempotency-Key로 결제 승인 재요청")
    fun testPaymentIdempotency() {
        every {
            bankServiceClient.withdraw(any(), any(), any(), any(), any())
        } returns
            BankTransactionResponse(
                transactionId = "BANK-TXN-001",
                accountId = 1L,
                amount = BigDecimal("10000"),
                balanceAfter = BigDecimal("90000"),
            )

        val createRequest =
            CreatePaymentRequest(
                userId = "user-123",
                accountId = 1L,
                amount = BigDecimal("10000"),
                description = "Idempotency test",
            )

        // First request
        val result1 =
            mockMvc
                .perform(
                    post("/api/payments")
                        .header("Idempotency-Key", "idempotent-payment-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)),
                ).andExpect(status().isOk)
                .andReturn()

        val paymentId1 = objectMapper.readTree(result1.response.contentAsString).get("paymentId").asText()

        // Second request with same key
        val result2 =
            mockMvc
                .perform(
                    post("/api/payments")
                        .header("Idempotency-Key", "idempotent-payment-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)),
                ).andExpect(status().isOk)
                .andReturn()

        val paymentId2 = objectMapper.readTree(result2.response.contentAsString).get("paymentId").asText()

        // Same payment returned
        assertThat(paymentId1).isEqualTo(paymentId2)

        // Only one payment created
        val payments = paymentRepository.findAll()
        assertThat(payments).hasSize(1)
    }

    @Test
    @DisplayName("취소 한도 초과 시 에러")
    fun testCancellationExceedsApprovedAmount() {
        every {
            bankServiceClient.withdraw(any(), any(), any(), any(), any())
        } returns
            BankTransactionResponse(
                transactionId = "BANK-TXN-001",
                accountId = 1L,
                amount = BigDecimal("5000"),
                balanceAfter = BigDecimal("95000"),
            )

        // Create payment of 5000
        val createRequest =
            CreatePaymentRequest(
                userId = "user-123",
                accountId = 1L,
                amount = BigDecimal("5000"),
                description = "Test payment",
            )

        val createResult =
            mockMvc
                .perform(
                    post("/api/payments")
                        .header("Idempotency-Key", "exceed-test-payment-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)),
                ).andReturn()

        val paymentId = objectMapper.readTree(createResult.response.contentAsString).get("paymentId").asText()

        // Try to cancel 6000 (more than approved amount)
        val cancelRequest =
            CancelPaymentRequest(
                amount = BigDecimal("6000"),
                reason = "Exceed test",
            )

        mockMvc
            .perform(
                post("/api/payments/$paymentId/cancel")
                    .header("Idempotency-Key", "exceed-cancel-001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(cancelRequest)),
            ).andExpect(status().isBadRequest)

        // Payment status unchanged
        val payment = paymentRepository.findByPaymentId(paymentId)!!
        assertThat(payment.status).isEqualTo(PaymentStatus.APPROVED)
        assertThat(payment.approvedAmount).isEqualTo(BigDecimal("5000.00"))
    }

    @Test
    @DisplayName("Bank Service 실패 시 결제 실패 처리")
    fun testBankServiceFailure() {
        every {
            bankServiceClient.withdraw(any(), any(), any(), any(), any())
        } throws RuntimeException("Bank service unavailable")

        val createRequest =
            CreatePaymentRequest(
                userId = "user-123",
                accountId = 1L,
                amount = BigDecimal("10000"),
                description = "Test payment",
            )

        mockMvc
            .perform(
                post("/api/payments")
                    .header("Idempotency-Key", "bank-fail-001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequest)),
            ).andExpect(status().isBadRequest)

        // Payment should be marked as FAILED
        val payment = paymentRepository.findByIdempotencyKey("bank-fail-001")
        assertThat(payment).isNotNull
        assertThat(payment!!.status).isEqualTo(PaymentStatus.FAILED)
    }

    // Removed test for missing Idempotency-Key header
    // This is framework-level validation (Spring validates required @RequestHeader)

    @Test
    @DisplayName("존재하지 않는 결제 조회 시 에러")
    fun testGetNonExistentPayment() {
        mockMvc
            .perform(get("/api/payments/NON-EXISTENT-ID"))
            .andExpect(status().isBadRequest)
    }
}
