package com.bank.payment.bank.service

import com.bank.payment.bank.domain.*
import com.bank.payment.bank.repository.AccountRepository
import com.bank.payment.bank.repository.LedgerRepository
import com.bank.payment.bank.repository.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

@Service
class TransactionService(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val ledgerRepository: LedgerRepository,
) {
    /**
     * 계좌에 입금을 처리합니다.
     *
     * ## 멱등성 (Idempotency)
     * - 동일한 idempotencyKey로 여러 번 호출해도 한 번만 처리됩니다
     * - Layer 1: 서비스 레벨 체크 (빠른 경로) - 기존 Transaction 반환
     * - Layer 2: DB unique 제약 (보장 경로) - 동시 요청 차단
     *
     * ## 동시성 제어 (Concurrency Control)
     * - Pessimistic Lock 사용 (SELECT FOR UPDATE)
     * - 계좌 잔액 업데이트 중 다른 트랜잭션의 읽기/쓰기 차단
     * - 트랜잭션 커밋 시 자동으로 락 해제
     *
     * ## Dual Recording (이중 기록)
     * - Transaction: 비즈니스 거래 기록 (조회/수정 가능)
     * - Ledger: 회계 감사 로그 (불변, append-only)
     * - 두 기록 모두 동일 트랜잭션 내에서 커밋 (원자성 보장)
     *
     * ## Balance Snapshot
     * - balanceAfter: 거래 후 잔액을 기록하여 변경 이력 추적 가능
     * - Ledger에는 balanceBefore/After 모두 기록 (이중 스냅샷)
     *
     * @param accountId 입금할 계좌 ID
     * @param amount 입금 금액 (양수)
     * @param idempotencyKey 멱등성 키 (UUID 권장) - 중복 방지
     * @param description 거래 설명 (선택)
     * @return 생성된 Transaction 객체
     * @throws IllegalArgumentException accountId가 존재하지 않거나, amount가 음수/0인 경우
     * @throws IllegalStateException 계좌 상태가 ACTIVE가 아닌 경우
     */
    @Transactional
    fun deposit(
        accountId: Long,
        amount: BigDecimal,
        idempotencyKey: String,
        description: String? = null,
    ): Transaction {
        // Check idempotency (Layer 1: Service-level check)
        val existingTransaction = transactionRepository.findByIdempotencyKey(idempotencyKey)
        if (existingTransaction != null) {
            return existingTransaction
        }

        // Validate amount
        require(amount > BigDecimal.ZERO) { "Amount must be positive" }

        // Lock account and get current balance (Pessimistic Lock)
        // SELECT FOR UPDATE: 다른 트랜잭션의 동시 읽기/쓰기 차단
        val account =
            accountRepository.findByIdForUpdate(accountId)
                ?: throw IllegalArgumentException("Account not found: $accountId")

        // Validate account status
        require(account.status == AccountStatus.ACTIVE) { "Account is not active" }

        val balanceBefore = account.balance
        val balanceAfter = balanceBefore + amount

        // Update account balance
        account.balance = balanceAfter
        account.updatedAt = LocalDateTime.now()
        accountRepository.save(account)

        // Create transaction record (비즈니스 기록)
        val transaction =
            Transaction(
                account = account,
                transactionId = generateTransactionId(),
                transactionType = TransactionType.DEPOSIT,
                amount = amount,
                balanceAfter = balanceAfter, // 스냅샷: 거래 후 잔액
                status = TransactionStatus.COMPLETED,
                idempotencyKey = idempotencyKey, // Layer 2: DB unique constraint
                description = description,
                completedAt = LocalDateTime.now(),
            )
        transactionRepository.save(transaction)

        // Create ledger entry (회계 감사 로그 - 불변)
        // CREDIT: 대변 (입금, 자산 증가)
        val ledger =
            Ledger(
                account = account,
                transactionId = transaction.transactionId,
                entryType = LedgerEntryType.CREDIT,
                amount = amount,
                balanceBefore = balanceBefore, // 이중 스냅샷: 거래 전
                balanceAfter = balanceAfter, // 이중 스냅샷: 거래 후
                idempotencyKey = "$idempotencyKey-ledger",
                memo = description,
            )
        ledgerRepository.save(ledger)

        return transaction
    }

    /**
     * 계좌에서 출금을 처리합니다.
     *
     * ## 멱등성 (Idempotency)
     * - 동일한 idempotencyKey로 여러 번 호출해도 한 번만 처리됩니다
     * - Layer 1: 서비스 레벨 체크 - 기존 Transaction 즉시 반환
     * - Layer 2: DB unique 제약 - 동시 요청을 DB 레벨에서 차단
     *
     * ## 동시성 제어 (Concurrency Control)
     * - **Pessimistic Lock 사용 (SELECT FOR UPDATE)**
     * - 잔액 차감 시 Race Condition 방지 (동시 출금으로 인한 마이너스 잔액 차단)
     * - 트레이드오프: 처리량 감소 ↔ 데이터 정합성 보장 (금융 시스템은 정합성 우선)
     *
     * ## 동시 출금 시나리오 예시
     * ```
     * 잔액: 10,000원
     * Thread A: 출금 6,000원
     * Thread B: 출금 5,000원
     *
     * Pessimistic Lock 없이:
     *   → A, B 모두 "잔액 충분" 판단
     *   → 최종 잔액: -1,000원 (오류!)
     *
     * Pessimistic Lock 적용:
     *   → A가 락 획득: 6,000원 출금 성공, 잔액 4,000원
     *   → B가 락 획득: 잔액 검증 실패 (4,000 < 5,000)
     *   → IllegalStateException 발생
     * ```
     *
     * ## Reference Tracking (참조 추적)
     * - referenceType/referenceId: 외부 시스템 연동 추적
     * - 예: Payment Service에서 호출 시
     *   - referenceType = "PAYMENT"
     *   - referenceId = "PAY-12345678"
     * - 정산(reconciliation) 시 Payment ↔ Transaction 매핑에 사용
     *
     * ## Dual Recording (이중 기록)
     * - Transaction: 비즈니스 기록 (상태 변경 가능)
     * - Ledger: 회계 감사 로그 (불변, DEBIT 차변 기록)
     *
     * @param accountId 출금할 계좌 ID
     * @param amount 출금 금액 (양수)
     * @param idempotencyKey 멱등성 키 (UUID 권장)
     * @param referenceType 참조 타입 (예: PAYMENT, TRANSFER)
     * @param referenceId 참조 ID (예: PAY-12345678)
     * @param description 거래 설명
     * @return 생성된 Transaction 객체
     * @throws IllegalArgumentException accountId가 존재하지 않거나, amount가 음수/0인 경우
     * @throws IllegalStateException 계좌 상태가 ACTIVE가 아니거나 잔액 부족인 경우
     */
    @Transactional
    fun withdraw(
        accountId: Long,
        amount: BigDecimal,
        idempotencyKey: String,
        referenceType: String? = null,
        referenceId: String? = null,
        description: String? = null,
    ): Transaction {
        // Check idempotency (Layer 1: Service-level check)
        val existingTransaction = transactionRepository.findByIdempotencyKey(idempotencyKey)
        if (existingTransaction != null) {
            return existingTransaction
        }

        // Validate amount
        require(amount > BigDecimal.ZERO) { "Amount must be positive" }

        // Pessimistic lock on account
        // SELECT FOR UPDATE: 동시 출금 차단 (Race Condition 방지)
        val account =
            accountRepository.findByIdForUpdate(accountId)
                ?: throw IllegalArgumentException("Account not found: $accountId")

        // Validate account status
        check(account.status == AccountStatus.ACTIVE) { "Account is not active" }

        // Check sufficient balance (락 보호 하에 검증)
        val balanceBefore = account.balance
        check(balanceBefore >= amount) {
            "Insufficient balance: current=$balanceBefore, requested=$amount"
        }

        val balanceAfter = balanceBefore - amount

        // Update account balance
        account.balance = balanceAfter
        account.updatedAt = LocalDateTime.now()
        accountRepository.save(account)

        // Create transaction record (비즈니스 기록)
        val transaction =
            Transaction(
                account = account,
                transactionId = generateTransactionId(),
                transactionType = TransactionType.WITHDRAW,
                amount = amount,
                balanceAfter = balanceAfter, // 스냅샷: 거래 후 잔액
                status = TransactionStatus.COMPLETED,
                referenceType = referenceType, // 외부 참조 추적 (Payment 등)
                referenceId = referenceId, // 외부 참조 ID
                idempotencyKey = idempotencyKey, // Layer 2: DB unique constraint
                description = description,
                completedAt = LocalDateTime.now(),
            )
        transactionRepository.save(transaction)

        // Create ledger entry (회계 감사 로그 - 불변)
        // DEBIT: 차변 (출금, 자산 감소)
        val ledger =
            Ledger(
                account = account,
                transactionId = transaction.transactionId,
                entryType = LedgerEntryType.DEBIT,
                amount = amount,
                balanceBefore = balanceBefore, // 이중 스냅샷: 거래 전
                balanceAfter = balanceAfter, // 이중 스냅샷: 거래 후
                referenceType = referenceType,
                referenceId = referenceId,
                idempotencyKey = "$idempotencyKey-ledger",
                memo = description,
            )
        ledgerRepository.save(ledger)

        return transaction
    }

    @Transactional(readOnly = true)
    fun getTransactionHistory(
        accountId: Long,
        page: Int = 0,
        size: Int = 20,
    ): org.springframework.data.domain.Page<Transaction> {
        val pageable =
            org.springframework.data.domain.PageRequest.of(
                page,
                size,
                org.springframework.data.domain.Sort.by("createdAt").descending(),
            )
        return transactionRepository.findByAccountId(accountId, pageable)
    }

    private fun generateTransactionId(): String {
        return "TXN-${UUID.randomUUID().toString().substring(0, 8).uppercase()}"
    }
}
