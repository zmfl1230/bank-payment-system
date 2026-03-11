package com.bank.payment.payment.service

import com.bank.payment.payment.client.BankServiceClient
import com.bank.payment.payment.domain.Payment
import com.bank.payment.payment.domain.PaymentStatus
import com.bank.payment.payment.domain.PaymentTransaction
import com.bank.payment.payment.domain.PaymentTransactionStatus
import com.bank.payment.payment.domain.PaymentTransactionType
import com.bank.payment.payment.repository.PaymentRepository
import com.bank.payment.payment.repository.PaymentTransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val paymentTransactionRepository: PaymentTransactionRepository,
    private val bankServiceClient: BankServiceClient,
) {
    /**
     * 결제 승인을 처리합니다 (Bank Service를 호출하여 실제 출금 수행).
     *
     * ## Saga Pattern (분산 트랜잭션)
     * ```
     * Step 1: Payment 생성 (PENDING)
     *   └─ 성공: 계속
     *   └─ 실패: 에러 반환 (Bank 호출 안 함)
     *
     * Step 2: Bank Service 호출 (출금)
     *   ├─ 성공: Payment → APPROVED
     *   └─ 실패: Payment → FAILED (보상: 고객에게 청구 안 함)
     *
     * Step 3: 각 DB 독립적으로 커밋 (최종 일관성)
     * ```
     *
     * ## 멱등성 (Idempotency)
     * - 동일한 idempotencyKey로 여러 번 호출 시 기존 Payment 반환
     * - 네트워크 타임아웃 후 재시도 시 중복 승인 방지
     * - Bank Service 호출 시에도 별도 idempotencyKey 전달 (이중 보호)
     *
     * ## 동시성 제어
     * - Payment 생성 시점에는 락 불필요 (새 레코드 생성)
     * - Bank Service에서 Pessimistic Lock으로 잔액 보호
     *
     * ## Reference Tracking
     * - Bank Transaction에 referenceType="PAYMENT", referenceId=paymentId 저장
     * - 정산(reconciliation) 시 Payment ↔ Bank Transaction 매핑에 사용
     *
     * ## Failure Handling (실패 처리)
     * - Bank Service 호출 실패 시:
     *   1. PaymentTransaction.status = FAILED
     *   2. Payment.status = FAILED
     *   3. 클라이언트에 에러 반환
     * - 재시도: 클라이언트가 동일 idempotencyKey로 재요청 가능
     *
     * ## 실제 플로우 예시
     * ```
     * Client: POST /api/payments (Idempotency-Key: abc123)
     *   → Payment DB: Payment (status=PENDING, approvedAmount=10000)
     *   → Payment DB: PaymentTransaction (type=APPROVAL, status=PENDING)
     *   → Bank API: POST /accounts/1/withdraw (Idempotency-Key: abc123-bank)
     *     → Bank DB: Transaction (amount=-10000, type=WITHDRAW)
     *     → Bank DB: Ledger (entryType=DEBIT)
     *   ← Bank API: { transactionId: "TXN-xyz" }
     *   → Payment DB: PaymentTransaction (status=COMPLETED, bankTransactionId=TXN-xyz)
     *   → Payment DB: Payment (status=APPROVED)
     * ← Client: { paymentId: "PAY-001", status: "APPROVED" }
     * ```
     *
     * @param userId 사용자 ID
     * @param accountId 출금할 계좌 ID (Bank Service의 Account)
     * @param amount 결제 금액 (양수)
     * @param idempotencyKey 멱등성 키 (UUID 권장)
     * @param merchantId 가맹점 ID (선택)
     * @param orderId 주문 ID (선택)
     * @param description 결제 설명
     * @return 생성된 Payment 객체 (status=APPROVED 또는 FAILED)
     * @throws IllegalArgumentException amount가 음수/0인 경우
     * @throws IllegalStateException Bank Service 호출 실패 시
     */
    @Transactional
    fun approvePayment(
        userId: String,
        accountId: Long,
        amount: BigDecimal,
        idempotencyKey: String,
        merchantId: String? = null,
        orderId: String? = null,
        description: String? = null,
    ): Payment {
        // 멱등성 체크: FAILED 상태는 재시도 허용
        // - 결제 시스템에서는 네트워크 타임아웃, 일시적 장애 등이 빈번히 발생
        // - FAILED는 "영구적 실패"가 아닌 "일시적 실패"로 간주하여 재시도 허용
        // - APPROVED 등 성공 상태는 중복 청구 방지를 위해 기존 결과 반환 (엄격한 멱등성)
        val existingPayment = paymentRepository.findByIdempotencyKey(idempotencyKey)
        if (existingPayment != null && existingPayment.status != PaymentStatus.FAILED) {
            return existingPayment
        }

        // Validate amount
        require(amount > BigDecimal.ZERO) { "Amount must be positive" }

        // Create payment (Saga Step 1: Payment 생성 - PENDING)
        val payment =
            Payment(
                paymentId = generatePaymentId(),
                userId = userId,
                accountId = accountId,
                totalAmount = amount, // 최초 승인 금액 (불변)
                approvedAmount = amount, // 현재 유효 금액 (취소 시 감소)
                cancelledAmount = BigDecimal.ZERO,
                status = PaymentStatus.PENDING,
                merchantId = merchantId,
                orderId = orderId,
                idempotencyKey = idempotencyKey,
                description = description,
            )
        paymentRepository.save(payment)

        // Create approval transaction (PaymentTransaction 생성 - PENDING)
        val transaction =
            PaymentTransaction(
                payment = payment,
                transactionId = generateTransactionId(),
                transactionType = PaymentTransactionType.APPROVAL,
                amount = amount,
                status = PaymentTransactionStatus.PENDING,
                idempotencyKey = "$idempotencyKey-approval",
            )
        paymentTransactionRepository.save(transaction)

        // Saga Step 2: Bank Service 호출 (실제 출금)
        try {
            val bankResponse =
                bankServiceClient.withdraw(
                    accountId = accountId,
                    amount = amount,
                    idempotencyKey = "$idempotencyKey-bank", // Bank Service용 별도 키
                    referenceType = "PAYMENT", // Reference tracking
                    referenceId = payment.paymentId, // Payment와 Bank Transaction 연결
                )

            // Saga Step 3: 성공 시 상태 업데이트
            // Update transaction with bank transaction ID (정산용 참조)
            transaction.bankTransactionId = bankResponse.transactionId
            transaction.status = PaymentTransactionStatus.COMPLETED
            transaction.completedAt = LocalDateTime.now()
            paymentTransactionRepository.save(transaction)

            // Update payment status (승인 완료)
            payment.status = PaymentStatus.APPROVED
            payment.updatedAt = LocalDateTime.now()
            paymentRepository.save(payment)

            return payment
        } catch (e: Exception) {
            // Saga 보상: Bank 호출 실패 시 Payment를 FAILED로 마킹
            // (고객에게 청구하지 않음)
            transaction.status = PaymentTransactionStatus.FAILED
            paymentTransactionRepository.save(transaction)

            payment.status = PaymentStatus.FAILED
            paymentRepository.save(payment)

            throw IllegalStateException("Payment approval failed: ${e.message}", e)
        }
    }

    /**
     * 결제 취소를 처리합니다 (부분 취소 및 전액 취소 지원).
     *
     * ## 부분 취소 설계
     * ```
     * 초기 승인: totalAmount=10000, approvedAmount=10000, cancelledAmount=0
     *
     * 1차 취소 3000원:
     *   → totalAmount=10000 (불변)
     *   → approvedAmount=7000 (10000-3000)
     *   → cancelledAmount=3000 (0+3000)
     *   → status=PARTIALLY_CANCELLED
     *
     * 2차 취소 7000원 (전액):
     *   → totalAmount=10000 (불변)
     *   → approvedAmount=0 (7000-7000)
     *   → cancelledAmount=10000 (3000+7000)
     *   → status=FULLY_CANCELLED
     *
     * 불변 조건: totalAmount == approvedAmount + cancelledAmount
     * ```
     *
     * ## 멱등성 (Idempotency)
     * - 동일한 idempotencyKey로 여러 번 호출 시 기존 Payment 반환
     * - 각 취소는 별도의 PaymentTransaction으로 기록
     * - Bank Service 호출 시에도 별도 idempotencyKey 전달
     *
     * ## 동시성 제어 (Optimistic Lock)
     * - Payment 엔티티의 @Version 필드 사용
     * - 동시 취소 시도 시 OptimisticLockException 발생
     * - 클라이언트가 재시도 (최신 데이터로 다시 검증)
     *
     * ## 동시 취소 시나리오
     * ```
     * 초기: version=1, approvedAmount=10000
     *
     * Thread A: 5000원 취소 (version=1 읽음)
     * Thread B: 6000원 취소 (version=1 읽음)
     *
     * Thread A 커밋: version→2, approvedAmount=5000
     * Thread B 커밋 시도: version 불일치 → OptimisticLockException
     *
     * Thread B 재시도: version=2, approvedAmount=5000
     *   → 검증: 5000 < 6000 → 에러 (취소 불가)
     * ```
     *
     * ## Saga Pattern (취소 플로우)
     * ```
     * Step 1: 검증 (approvedAmount >= cancelAmount)
     *   └─ 실패: 에러 반환
     *
     * Step 2: PaymentTransaction 생성 (CANCEL, PENDING)
     *
     * Step 3: Bank Service 호출 (입금/환불)
     *   ├─ 성공: Payment 상태 업데이트
     *   └─ 실패: PaymentTransaction만 FAILED (Payment 상태 유지)
     *           → 클라이언트 재시도 가능
     * ```
     *
     * ## amount 파라미터
     * - null: 전액 취소 (approvedAmount 전체)
     * - 지정: 부분 취소 (지정 금액만큼)
     * - 검증: cancelAmount <= approvedAmount
     *
     * ## 실제 사용 예시
     * ```
     * // e-commerce 부분 환불 케이스
     * 결제 승인: 10,000원 (상품 A: 7000, 상품 B: 3000)
     * 배송 중 상품 B 파손
     *   → cancelPayment(paymentId, amount=3000, reason="상품 파손")
     *   → 상품 A는 정상 배송 (7000원 유효)
     * ```
     *
     * @param paymentId 취소할 결제 ID (PAY-XXXXXXXX)
     * @param amount 취소 금액 (null이면 전액 취소)
     * @param idempotencyKey 멱등성 키 (UUID 권장)
     * @param reason 취소 사유 (고객 서비스/정산용)
     * @return 업데이트된 Payment 객체 (status=PARTIALLY_CANCELLED 또는 FULLY_CANCELLED)
     * @throws IllegalArgumentException paymentId가 존재하지 않거나 amount가 음수/0인 경우
     * @throws IllegalStateException payment 상태가 취소 불가능하거나 취소 금액이 초과한 경우
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException 동시 취소 충돌 시
     */
    @Transactional
    fun cancelPayment(
        paymentId: String,
        amount: BigDecimal?,
        idempotencyKey: String,
        reason: String? = null,
    ): Payment {
        // Check idempotency (멱등성 체크 - 기존 취소 건 반환)
        val existingTransaction = paymentTransactionRepository.findByIdempotencyKey(idempotencyKey)
        if (existingTransaction != null) {
            return existingTransaction.payment
        }

        // Lock payment (Optimistic Lock - @Version 필드 사용)
        val payment =
            paymentRepository.findByPaymentId(paymentId)
                ?: throw IllegalArgumentException("Payment not found: $paymentId")

        // Validate payment status (취소 가능 상태 검증)
        check(payment.status == PaymentStatus.APPROVED || payment.status == PaymentStatus.PARTIALLY_CANCELLED) {
            "Payment cannot be cancelled in ${payment.status} status"
        }

        // Determine cancellation amount (amount null이면 전액 취소)
        val cancelAmount = amount ?: payment.approvedAmount
        require(cancelAmount > BigDecimal.ZERO) { "Cancel amount must be positive" }
        require(cancelAmount <= payment.approvedAmount) {
            "Cancel amount ($cancelAmount) exceeds approved amount (${payment.approvedAmount})"
        }

        // Create cancellation transaction (PaymentTransaction 생성 - PENDING)
        val transaction =
            PaymentTransaction(
                payment = payment,
                transactionId = generateTransactionId(),
                transactionType = PaymentTransactionType.CANCEL,
                amount = cancelAmount,
                status = PaymentTransactionStatus.PENDING,
                idempotencyKey = idempotencyKey,
                reason = reason, // 취소 사유 저장 (CS/정산용)
            )
        paymentTransactionRepository.save(transaction)

        // Saga Step: Bank Service 호출 (입금/환불)
        try {
            val bankResponse =
                bankServiceClient.deposit(
                    accountId = payment.accountId,
                    amount = cancelAmount,
                    idempotencyKey = "$idempotencyKey-bank", // Bank Service용 별도 키
                    referenceType = "PAYMENT_CANCEL", // Reference tracking
                    referenceId = payment.paymentId,
                )

            // Update transaction (정산용 참조 저장)
            transaction.bankTransactionId = bankResponse.transactionId
            transaction.status = PaymentTransactionStatus.COMPLETED
            transaction.completedAt = LocalDateTime.now()
            paymentTransactionRepository.save(transaction)

            // Update payment (부분/전액 취소 상태 업데이트)
            payment.approvedAmount = payment.approvedAmount - cancelAmount
            payment.cancelledAmount = payment.cancelledAmount + cancelAmount
            payment.status =
                if (payment.approvedAmount == BigDecimal.ZERO) {
                    PaymentStatus.FULLY_CANCELLED // 전액 취소
                } else {
                    PaymentStatus.PARTIALLY_CANCELLED // 부분 취소
                }
            payment.updatedAt = LocalDateTime.now()
            // save 시 @Version 체크 → 동시 취소 충돌 감지
            paymentRepository.save(payment)

            return payment
        } catch (e: Exception) {
            // Saga 보상: Bank 호출 실패 시 PaymentTransaction만 FAILED
            // (Payment 상태는 유지 → 클라이언트 재시도 가능)
            transaction.status = PaymentTransactionStatus.FAILED
            paymentTransactionRepository.save(transaction)

            throw IllegalStateException("Payment cancellation failed: ${e.message}", e)
        }
    }

    @Transactional(readOnly = true)
    fun getPayment(paymentId: String): Payment {
        return paymentRepository.findByPaymentId(paymentId)
            ?: throw IllegalArgumentException("Payment not found: $paymentId")
    }

    private fun generatePaymentId(): String = "PAY-${UUID.randomUUID().toString().substring(0, 8).uppercase()}"

    private fun generateTransactionId(): String = "PTXN-${UUID.randomUUID().toString().substring(0, 8).uppercase()}"
}
