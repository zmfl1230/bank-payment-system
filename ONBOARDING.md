# Bank Payment System - Onboarding Guide

신규 개발자를 위한 은행 결제 시스템 온보딩 가이드입니다.

---

## 목차

1. [프로젝트 개요](#프로젝트-개요)
2. [아키텍처 설계 원칙](#아키텍처-설계-원칙)
3. [Entity 컬럼 가이드](#entity-컬럼-가이드)
4. [API 설계 근거](#api-설계-근거)
5. [운영 고려사항](#운영-고려사항)
6. [주요 시나리오 플로우](#주요-시나리오-플로우)
7. [트러블슈팅 가이드](#트러블슈팅-가이드)
8. [개발 환경 설정](#개발-환경-설정)

---

## 프로젝트 개요

### 시스템 구성

```
┌─────────────────────────────────────┐
│     Client Applications             │
└──────────────┬──────────────────────┘
               │
       ┌───────┴────────┐
       │                │
   (8080)           (8081)
       │                │
┌──────▼────────┐  ┌───▼──────────┐
│ Bank Service  │◄─┤ Payment      │
│               │  │ Service      │
│ - Account     │  │              │
│ - Transaction │  │ - Payment    │
│ - Ledger      │  │ - Cancel     │
└──────┬────────┘  └───┬──────────┘
       │               │
   PostgreSQL      PostgreSQL
   (5432)          (5433)
```

**두 개의 독립적인 마이크로서비스**:

1. **Bank Service** (포트 8080)
   - 계좌 관리 (생성, 조회)
   - 입출금 처리
   - 거래 내역 관리
   - 복식부기 준비 (Ledger)

2. **Payment Service** (포트 8081)
   - 결제 승인
   - 부분/전체 취소
   - Bank Service를 호출해서 실제 출금/입금 처리

### 핵심 특징

- **독립 데이터베이스**: 각 서비스는 자체 PostgreSQL DB 사용
- **동기 통신**: Payment → Bank는 REST API 호출
- **멱등성 보장**: 모든 상태 변경 API는 Idempotency-Key 필수
- **이중 기록**: Transaction (비즈니스) + Ledger (회계 감사)
- **하이브리드 락**: Pessimistic (잔액 변경) + Optimistic (상태 업데이트)

---

## 아키텍처 설계 원칙

### 1. 멱등성 우선 (Idempotency-First)

**문제**: 네트워크 장애로 인한 재시도 시 중복 처리 방지

**해결**: 2단계 멱등성 보장

```kotlin
// Layer 1: Service 레벨 (빠른 경로)
fun withdraw(idempotencyKey: String, ...): Transaction {
    val existing = transactionRepository.findByIdempotencyKey(idempotencyKey)
    if (existing != null) return existing  // 캐싱된 결과 즉시 반환

    // Layer 2: DB 제약 (보장 경로)
    // idempotency_key UNIQUE 제약으로 동시 요청 차단
    val transaction = Transaction(idempotencyKey = idempotencyKey, ...)
    return transactionRepository.save(transaction)
}
```

**실제 사례**:
```bash
# 첫 요청
curl -H "Idempotency-Key: abc123" -X POST .../withdraw
# → 출금 처리

# 네트워크 타임아웃 후 재시도
curl -H "Idempotency-Key: abc123" -X POST .../withdraw
# → 기존 결과 반환 (중복 출금 없음)
```

### 2. Dual Recording (이중 기록)

**문제**: 거래 기록과 회계 감사 추적을 분리해야 함

**해결**: Transaction + Ledger 동시 기록

| Transaction | Ledger |
|-------------|--------|
| 비즈니스 기록 | 회계 감사 로그 |
| 상태 변경 가능 (PENDING → COMPLETED) | 불변 (Immutable) |
| 복합 정보 (description, reference) | 순수 회계 항목 |
| 조회/수정 빈번 | 추가만 가능 (append-only) |

**예시**:
```kotlin
// 출금 시 동시 기록
@Transactional
fun withdraw(...): Transaction {
    // 1. Transaction 기록
    val transaction = Transaction(
        type = WITHDRAW,
        amount = 5000,
        balanceAfter = 10000,  // 스냅샷
        status = COMPLETED
    )

    // 2. Ledger 기록 (동일 트랜잭션 내)
    val ledger = Ledger(
        entryType = DEBIT,     // 차변 (출금)
        amount = 5000,
        balanceBefore = 15000,
        balanceAfter = 10000,
        transactionId = transaction.transactionId
    )

    // 두 개 모두 커밋 또는 롤백
}
```

### 3. Saga Pattern (분산 트랜잭션)

**문제**: Payment Service와 Bank Service는 독립된 DB → 2PC 불가

**해결**: 보상 트랜잭션(Compensation)으로 최종 일관성 확보

```
결제 승인 Saga:
┌─────────────────────────────────────────────────────┐
│ 1. Payment DB에 PENDING 생성                         │
│    └─ 실패: 에러 반환 (Bank 호출 안 함)               │
│                                                      │
│ 2. Bank Service 호출 (출금)                          │
│    ├─ 성공: Payment → APPROVED                      │
│    └─ 실패: Payment → FAILED (보상: 청구 안 함)       │
│                                                      │
│ 3. 각 DB는 독립적으로 커밋                            │
└─────────────────────────────────────────────────────┘

결제 취소 Saga:
┌─────────────────────────────────────────────────────┐
│ 1. PaymentTransaction (CANCEL, PENDING) 생성         │
│                                                      │
│ 2. Bank Service 호출 (입금/환불)                      │
│    ├─ 성공: Payment → PARTIALLY_CANCELLED            │
│    └─ 실패: Transaction → FAILED (재시도 가능)        │
└─────────────────────────────────────────────────────┘
```

### 4. 하이브리드 락 (Hybrid Locking)

**Pessimistic Lock** - Bank Service 잔액 변경:
```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id = :id")
fun findByIdForUpdate(id: Long): Account?
```

**언제**: 입출금 처리 (잔액 변경)
**이유**: Race condition 방지 - 동시 출금으로 잔액 마이너스 방지
**트레이드오프**: 처리량 감소 ↔ 정합성 보장 (금융에서는 정합성 우선)

**Optimistic Lock** - Payment Service 상태 변경:
```kotlin
@Entity
data class Payment(
    @Version var version: Long,  // JPA가 자동 관리
    ...
)
```

**언제**: Payment 상태 업데이트 (취소 처리 등)
**이유**: 충돌 빈도 낮음 + 처리량 우선
**실패 시**: `OptimisticLockException` → 클라이언트 재시도

#### 락 전략 선택 배경

**비관적 락 선택 이유 (Account)**:
- **MVP 우선**: 단일 DB 환경에서 가장 단순하고 안정적
- **충분한 성능**: ~1,000 TPS/account까지 지원 (현재 요구사항 대비 충분)
- **명확한 ACID**: DB 트랜잭션으로 정합성 보장 (금융 시스템 우선순위)

**분산락 도입은**:
- MSA 전환 시
- Read Replica 사용 시
- **실제 성능 병목 측정 후**에만 고려

---

## Entity 컬럼 가이드

### Account (계좌)

| 컬럼 | 타입 | 목적 | 주의사항 |
|------|------|------|----------|
| `id` | Long | Primary Key | 자동 증가 |
| `user_id` | Long | 사용자 FK | users 테이블 참조 |
| `account_number` | String | 고유 계좌번호 (10자리) | **유일성 보장** - 사용자 대면 ID |
| `account_type` | Enum | 계좌 유형 (CHECKING, SAVINGS) | 향후 이자 정책 분리 가능 |
| `balance` | BigDecimal | 현재 잔액 | **실시간 업데이트** - Pessimistic lock으로 보호 |
| `status` | Enum | 계좌 상태 (ACTIVE, FROZEN, CLOSED) | FROZEN 시 출금 차단 |
| `currency` | String | 통화 (기본: KRW) | 다중 통화 지원 준비 |
| `version` | Long | 낙관적 락 버전 | **동시 수정 감지** - 현재 사용 안 함 (Pessimistic 우선) |
| `created_at` | Timestamp | 계좌 생성 시각 | Immutable |
| `updated_at` | Timestamp | 마지막 변경 시각 | 자동 업데이트 |

**설계 의도**:
- `balance`는 실시간 조회 성능을 위해 Account 테이블에 비정규화
- `version`은 미래 확장성을 위해 남겨둠 (현재는 Pessimistic lock 사용)

---

### Transaction (거래 내역)

| 컬럼 | 타입 | 목적 | 주의사항 |
|------|------|------|----------|
| `id` | Long | Primary Key | 자동 증가 |
| `account_id` | Long | 계좌 FK | 인덱스 필수 (거래 내역 조회 빈번) |
| `transaction_id` | String | 비즈니스 고유 ID (UUID) | **유일성 보장** - 외부 시스템 참조용 |
| `transaction_type` | Enum | 거래 유형 | DEPOSIT, WITHDRAW, PAYMENT, REFUND 등 |
| `amount` | BigDecimal | 거래 금액 | 항상 양수 (방향은 type으로 구분) |
| `balance_after` | BigDecimal | **거래 후 잔액 스냅샷** | **핵심**: 각 거래 시점의 잔액 기록 |
| `status` | Enum | 거래 상태 | PENDING, COMPLETED, FAILED |
| `reference_type` | String | 참조 타입 | PAYMENT, TRANSFER 등 |
| `reference_id` | String | 참조 ID | Payment Service의 paymentId 등 |
| `idempotency_key` | String | 멱등성 키 | **유일성 보장** - 중복 방지 |
| `description` | String | 거래 설명 | 사용자 대면 메시지 |
| `created_at` | Timestamp | 거래 생성 시각 | 정렬/필터링에 사용 |
| `completed_at` | Timestamp | 완료 시각 | PENDING일 때는 null |

**balance_after의 중요성**:
```sql
-- "잔액이 왜 변했지?" 추적 가능
SELECT transaction_id, amount, balance_after, created_at
FROM transaction
WHERE account_id = 1
ORDER BY created_at DESC;

-- 결과:
-- TXN-003: -3000원, balance_after: 5000원  ← 현재
-- TXN-002: -2000원, balance_after: 8000원
-- TXN-001: +10000원, balance_after: 10000원 ← 최초
```

**reference_type/id의 활용**:
```kotlin
// Payment Service에서 Bank Service 호출 시
bankService.withdraw(
    referenceType = "PAYMENT",
    referenceId = "PAY-12345678"  // Payment의 paymentId
)

// 나중에 reconciliation (정산) 시
val bankTxns = transactionRepository.findByReferenceTypeAndReferenceId("PAYMENT", "PAY-12345678")
// → Payment와 Bank Transaction 매핑
```

---

### Ledger (원장 - 회계 감사)

| 컬럼 | 타입 | 목적 | 주의사항 |
|------|------|------|----------|
| `id` | Long | Primary Key | 자동 증가 |
| `account_id` | Long | 계좌 FK | 인덱스 필수 |
| `transaction_id` | String | Transaction 참조 | 1:1 매핑 (idempotency_key로 보장) |
| `entry_type` | Enum | **DEBIT (차변/출금) / CREDIT (대변/입금)** | 복식부기 준비 |
| `amount` | BigDecimal | 거래 금액 | 항상 양수 |
| `balance_before` | BigDecimal | **거래 전 잔액** | Transaction에는 없는 정보 |
| `balance_after` | BigDecimal | **거래 후 잔액** | Transaction과 동일 |
| `reference_type` | String | 참조 타입 | Transaction과 동일 |
| `reference_id` | String | 참조 ID | Transaction과 동일 |
| `idempotency_key` | String | 멱등성 키 | **유일성 보장** - Transaction과 동일 키 |
| `memo` | String | 메모 | 내부 감사용 |
| `created_at` | Timestamp | 생성 시각 | **Immutable** - 수정/삭제 금지 |

**Ledger의 핵심 특징**:

1. **Immutable (불변성)**:
   ```kotlin
   // ❌ 절대 금지
   ledger.amount = newAmount
   ledger.memo = "수정"

   // ✅ 역거래로 취소 표현
   val reversalLedger = Ledger(
       entryType = if (original.entryType == DEBIT) CREDIT else DEBIT,
       amount = original.amount,
       memo = "Reversal of ${original.id}"
   )
   ```

2. **이중 스냅샷**:
   ```kotlin
   // Transaction: after만 기록
   Transaction(balanceAfter = 10000)

   // Ledger: before/after 모두 기록
   Ledger(
       balanceBefore = 15000,
       balanceAfter = 10000
   )
   // → 변경 폭 추적 가능 (15000 → 10000 = -5000)
   ```

3. **복식부기 준비**:
   ```kotlin
   // 출금 5000원
   Ledger(
       entryType = DEBIT,   // 차변 (자산 감소)
       amount = 5000,
       account = myAccount
   )

   // 향후 이체 기능 추가 시:
   // 송금인 계좌: DEBIT 5000원
   // 수취인 계좌: CREDIT 5000원
   // → 차변 = 대변 균형
   ```

---

### Payment (결제)

| 컬럼 | 타입 | 목적 | 주의사항 |
|------|------|------|----------|
| `id` | Long | Primary Key | 자동 증가 |
| `payment_id` | String | 비즈니스 고유 ID (PAY-XXXXXXXX) | **유일성 보장** - 외부 노출용 |
| `user_id` | String | 사용자 ID | Bank Service의 user_id와 별도 (서비스 독립성) |
| `account_id` | Long | 계좌 ID | Bank Service 계좌 참조 (API로만 접근) |
| `total_amount` | BigDecimal | **최초 승인 금액 (불변)** | 영수증용 - 절대 변경 금지 |
| `approved_amount` | BigDecimal | **현재 유효 금액** | 취소 시 감소 (부분 취소 지원) |
| `cancelled_amount` | BigDecimal | **누적 취소 금액** | 취소 시 증가 |
| `status` | Enum | 결제 상태 | APPROVED → PARTIALLY_CANCELLED → FULLY_CANCELLED |
| `merchant_id` | String | 가맹점 ID | 선택적 |
| `order_id` | String | 주문 ID | 선택적 (e-commerce 연동용) |
| `idempotency_key` | String | 멱등성 키 | **유일성 보장** |
| `description` | String | 결제 설명 | 사용자 대면 메시지 |
| `version` | Long | 낙관적 락 버전 | **동시 취소 방지** |
| `created_at` | Timestamp | 승인 시각 | Immutable |
| `updated_at` | Timestamp | 마지막 변경 시각 | 취소 시 업데이트 |

**부분 취소 설계**:
```kotlin
// 초기 승인: 10,000원
Payment(
    totalAmount = 10000,
    approvedAmount = 10000,
    cancelledAmount = 0,
    status = APPROVED
)

// 1차 부분 취소: 3,000원
Payment(
    totalAmount = 10000,      // 불변
    approvedAmount = 7000,    // 10000 - 3000
    cancelledAmount = 3000,   // 0 + 3000
    status = PARTIALLY_CANCELLED
)

// 2차 부분 취소: 7,000원 (전액)
Payment(
    totalAmount = 10000,      // 불변
    approvedAmount = 0,       // 7000 - 7000
    cancelledAmount = 10000,  // 3000 + 7000
    status = FULLY_CANCELLED
)

// 불변 조건: totalAmount == approvedAmount + cancelledAmount
```

**version 필드의 역할**:
```kotlin
// 동시 취소 시나리오
Thread 1: cancelPayment(amount = 5000)  // version = 1
Thread 2: cancelPayment(amount = 6000)  // version = 1

// Thread 1이 먼저 커밋: version → 2
// Thread 2 커밋 시도: OptimisticLockException (version 불일치)
// → Thread 2는 재시도 (최신 데이터로 다시 검증)
```

---

### PaymentTransaction (결제 트랜잭션)

| 컬럼 | 타입 | 목적 | 주의사항 |
|------|------|------|----------|
| `id` | Long | Primary Key | 자동 증가 |
| `payment_id` | Long | Payment FK | 하나의 Payment에 여러 Transaction |
| `transaction_id` | String | 비즈니스 고유 ID (PTXN-XXXXXXXX) | **유일성 보장** |
| `transaction_type` | Enum | **APPROVAL (승인) / CANCEL (취소)** | 이벤트 타입 |
| `amount` | BigDecimal | 거래 금액 | 항상 양수 |
| `status` | Enum | 처리 상태 | PENDING, COMPLETED, FAILED |
| `bank_transaction_id` | String | **Bank Service Transaction 참조** | 정산/reconciliation 핵심 |
| `idempotency_key` | String | 멱등성 키 | **유일성 보장** |
| `reason` | String | 취소 사유 | CANCEL 타입일 때만 사용 |
| `retry_count` | Int | 재시도 횟수 | 향후 자동 재시도 로직용 |
| `created_at` | Timestamp | 생성 시각 | 정렬용 |
| `completed_at` | Timestamp | 완료 시각 | PENDING일 때는 null |

**bank_transaction_id의 중요성**:
```kotlin
// Payment Service → Bank Service 호출
val bankTxn = bankService.withdraw(...)
// bankTxn.transactionId = "TXN-abc123"

// PaymentTransaction에 저장
PaymentTransaction(
    bankTransactionId = "TXN-abc123",  // 참조 저장
    ...
)

// 나중에 정산 시
val paymentTxn = paymentTransactionRepository.findByBankTransactionId("TXN-abc123")
val bankTxn = bankTransactionRepository.findByTransactionId("TXN-abc123")
// → 양쪽 데이터 비교 (금액, 시각 등)
```

**retry_count 활용 (향후)**:
```kotlin
fun retryFailedPayment(paymentTransactionId: Long) {
    val txn = repository.findById(paymentTransactionId)
    if (txn.retryCount >= MAX_RETRY) {
        throw TooManyRetriesException()
    }

    txn.retryCount++
    // 재시도 로직...
}
```

---

## API 설계 근거

### 왜 Idempotency-Key를 헤더로 받나?

**설계 결정**:
```http
POST /api/accounts/1/withdraw
Headers:
  Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Body:
  { "amount": 5000 }
```

**근거**:
1. **RFC 표준**: HTTP Idempotency-Key 헤더 제안 (draft-ietf-httpapi-idempotency-key-header)
2. **비즈니스 데이터 분리**: 요청 본문은 순수 비즈니스 데이터만
3. **재사용성**: 동일한 Body로 여러 번 호출 가능 (Key만 바꿔서)
4. **로깅/모니터링**: 헤더는 미들웨어에서 쉽게 추출

**대안과 비교**:
```kotlin
// ❌ Body에 포함
{ "idempotencyKey": "...", "amount": 5000 }
// 문제: 비즈니스 데이터와 프로토콜 메타데이터 혼재

// ✅ 헤더 사용
Headers: Idempotency-Key
Body: { "amount": 5000 }
// 장점: 관심사 분리
```

---

### 왜 reference_type과 reference_id를 분리했나?

**설계 결정**:
```kotlin
Transaction(
    referenceType = "PAYMENT",
    referenceId = "PAY-12345678"
)
```

**근거**:
1. **타입 안정성**: Enum으로 referenceType 제어 가능
2. **쿼리 최적화**: 복합 인덱스 활용 `(reference_type, reference_id)`
3. **확장성**: TRANSFER, LOAN 등 다른 타입 추가 용이
4. **정산 효율**: Type별로 배치 처리 가능

**실제 활용**:
```sql
-- Type별 집계
SELECT reference_type, COUNT(*), SUM(amount)
FROM transaction
WHERE created_at >= '2024-01-01'
GROUP BY reference_type;

-- 특정 Payment 추적
SELECT * FROM transaction
WHERE reference_type = 'PAYMENT' AND reference_id = 'PAY-12345678';
```

---

### 왜 부분 취소를 지원하나?

**설계 결정**:
```http
POST /api/payments/PAY-12345/cancel
Body:
  { "amount": 3000 }  # 일부만 취소
```

**실제 사례**:
```
사용자가 10,000원 결제 승인
 └─ 상품 A: 7,000원
 └─ 상품 B: 3,000원

배송 중 상품 B만 파손
 → 3,000원 부분 취소
 → 상품 A는 정상 배송 (7,000원 유지)
```

**구현 방식**:
```kotlin
fun cancelPayment(paymentId: String, amount: BigDecimal?): Payment {
    val payment = findPayment(paymentId)

    // amount 생략 시 전액 취소
    val cancelAmount = amount ?: payment.approvedAmount

    // 검증: 남은 금액보다 큰 취소 불가
    require(payment.approvedAmount >= cancelAmount) {
        "Cancel amount exceeds approved amount"
    }

    // 상태 업데이트
    payment.approvedAmount -= cancelAmount
    payment.cancelledAmount += cancelAmount
    payment.status = if (payment.approvedAmount == BigDecimal.ZERO) {
        FULLY_CANCELLED
    } else {
        PARTIALLY_CANCELLED
    }

    return payment
}
```

---

### 왜 GET /balance와 GET /account를 분리했나?

**설계 결정**:
```http
GET /api/accounts/1/balance  # 가벼운 조회
GET /api/accounts/1          # 전체 정보
```

**근거**:
1. **성능 최적화**: 잔액만 필요한 경우 불필요한 데이터 전송 없음
2. **캐싱 전략**: `/balance`는 짧은 TTL, `/account`는 긴 TTL 가능
3. **사용 빈도**: 잔액 조회가 훨씬 빈번함
4. **보안**: 민감 정보 (계좌번호 등) 노출 최소화

**응답 비교**:
```json
// GET /api/accounts/1/balance (가벼움)
{
  "accountId": 1,
  "balance": 50000,
  "currency": "KRW",
  "status": "ACTIVE",
  "lastUpdated": "2024-01-15T10:30:00"
}

// GET /api/accounts/1 (전체)
{
  "id": 1,
  "accountNumber": "1234567890",
  "userId": 100,
  "accountType": "CHECKING",
  "balance": 50000,
  "currency": "KRW",
  "status": "ACTIVE",
  "createdAt": "2024-01-01T09:00:00",
  "updatedAt": "2024-01-15T10:30:00"
}
```

---

## 운영 고려사항

### 1. 동시성 제어 상황별 가이드

#### 시나리오 A: 동시 출금

**문제**:
```
계좌 잔액: 10,000원

동시 요청:
- Thread 1: 출금 6,000원
- Thread 2: 출금 5,000원

락 없이 처리 시:
- 두 요청 모두 "잔액 충분" 판단
- 최종 잔액: -1,000원 (오류!)
```

**해결**: Pessimistic Lock
```kotlin
@Transactional
fun withdraw(accountId: Long, amount: BigDecimal): Transaction {
    // 1. 계좌 락 획득 (SELECT FOR UPDATE)
    val account = accountRepository.findByIdForUpdate(accountId)
        ?: throw IllegalArgumentException("Account not found")

    // 2. 잔액 검증 (락 보호 하에)
    require(account.balance >= amount) { "Insufficient balance" }

    // 3. 잔액 차감
    account.balance -= amount

    // 4. 트랜잭션 기록
    // ...

    // 5. 락 해제 (트랜잭션 커밋 시)
}
```

**결과**:
```
Thread 1이 먼저 락 획득
 → 6,000원 출금 성공
 → 잔액: 4,000원
 → 락 해제

Thread 2가 락 획득
 → 잔액 검증: 4,000원 < 5,000원
 → 에러: "Insufficient balance"
```

#### 시나리오 B: 동시 취소

**문제**:
```
Payment: approvedAmount = 10,000원

동시 요청:
- Thread 1: 5,000원 취소
- Thread 2: 6,000원 취소

낙관적 락 없이 처리 시:
- 두 요청 모두 "10,000원 >= 취소액" 판단
- 최종 approvedAmount: -1,000원 (오류!)
```

**해결**: Optimistic Lock
```kotlin
@Entity
data class Payment(
    @Version var version: Long = 0,  // JPA가 자동 관리
    var approvedAmount: BigDecimal,
    ...
)

@Transactional
fun cancelPayment(paymentId: String, amount: BigDecimal): Payment {
    val payment = paymentRepository.findByPaymentId(paymentId)
        ?: throw IllegalArgumentException("Payment not found")

    // 검증
    require(payment.approvedAmount >= amount) { ... }

    // 업데이트 (version 자동 증가)
    payment.approvedAmount -= amount
    payment.cancelledAmount += amount

    // save 시 version 충돌 감지
    return paymentRepository.save(payment)
}
```

**결과**:
```
초기: version = 1, approvedAmount = 10,000

Thread 1 시작: version = 1 읽음
Thread 2 시작: version = 1 읽음

Thread 1 커밋:
 → version = 2로 증가
 → approvedAmount = 5,000

Thread 2 커밋 시도:
 → version = 1로 업데이트 시도
 → DB의 version = 2와 불일치
 → OptimisticLockException 발생!

Thread 2 재시도:
 → version = 2, approvedAmount = 5,000 읽음
 → 검증: 5,000 < 6,000 → 에러
```

### 2. 멱등성 시나리오

#### 시나리오 C: 네트워크 타임아웃 재시도

**상황**:
```
1. 클라이언트: 출금 요청 전송
2. Bank Service: 처리 완료
3. 네트워크: 응답 전송 중 타임아웃
4. 클라이언트: "타임아웃이니 재시도해야지"
```

**멱등성 없이**:
```http
POST /api/accounts/1/withdraw
Body: { "amount": 5000 }

# 첫 요청: 5000원 출금 성공
# 재시도: 5000원 또 출금 (중복!)
```

**멱등성 적용**:
```http
# 첫 요청
POST /api/accounts/1/withdraw
Headers:
  Idempotency-Key: abc-123
Body:
  { "amount": 5000 }

# 응답: { "transactionId": "TXN-001", "balanceAfter": 10000 }

# 타임아웃 후 재시도 (동일한 Idempotency-Key)
POST /api/accounts/1/withdraw
Headers:
  Idempotency-Key: abc-123
Body:
  { "amount": 5000 }

# 응답: { "transactionId": "TXN-001", "balanceAfter": 10000 }
#      (동일한 결과 반환, 중복 출금 없음!)
```

**구현 로직**:
```kotlin
fun withdraw(idempotencyKey: String, ...): Transaction {
    // 1. 기존 거래 확인
    val existing = transactionRepository.findByIdempotencyKey(idempotencyKey)
    if (existing != null) {
        logger.info("Idempotent request detected: $idempotencyKey")
        return existing  // 캐싱된 결과 반환
    }

    // 2. 새 거래 처리
    // ...
}
```

### 3. Saga 패턴 실패 케이스

#### 시나리오 D: Bank Service 장애

**상황**: Payment 승인 중 Bank Service가 다운

```kotlin
fun approvePayment(...): Payment {
    // 1. Payment 생성 (PENDING)
    val payment = Payment(status = PENDING, ...)
    paymentRepository.save(payment)

    // 2. PaymentTransaction 생성 (PENDING)
    val txn = PaymentTransaction(type = APPROVAL, status = PENDING, ...)
    paymentTransactionRepository.save(txn)

    try {
        // 3. Bank Service 호출
        val bankTxn = bankService.withdraw(...)  // ← 여기서 실패!

        // 4. 성공 시 업데이트
        txn.status = COMPLETED
        txn.bankTransactionId = bankTxn.transactionId
        payment.status = APPROVED
    } catch (e: Exception) {
        // 5. 실패 시 보상
        txn.status = FAILED
        payment.status = FAILED
        // Bank 호출 안 했거나 실패 → 고객에게 청구 안 함 (보상)
        throw PaymentFailedException("Bank service error", e)
    }

    return paymentRepository.save(payment)
}
```

**결과**:
```
Payment DB:
- Payment: status = FAILED
- PaymentTransaction: status = FAILED

Bank DB:
- (변경 없음 - 호출 실패했으므로)

클라이언트:
- 에러 응답 수신
- 재시도 가능 (동일 Idempotency-Key로)
```

**재시도 시**:
```http
# 동일한 Idempotency-Key로 재시도
POST /api/payments
Headers:
  Idempotency-Key: payment-123

# 멱등성 체크:
# - Payment.idempotencyKey = "payment-123" 존재
# - 하지만 status = FAILED
# → 재시도 허용 (새로운 PaymentTransaction 생성)
```

### 4. 데이터 정합성 검증

#### Reconciliation (정산) 절차

**목적**: Payment Service와 Bank Service 간 데이터 일치 확인

```kotlin
fun reconcilePayments(date: LocalDate) {
    // 1. Payment Service에서 당일 승인 건 조회
    val payments = paymentRepository.findByCreatedAtBetween(
        date.atStartOfDay(),
        date.plusDays(1).atStartOfDay()
    ).filter { it.status == APPROVED }

    // 2. 각 Payment의 PaymentTransaction 조회
    payments.forEach { payment ->
        val paymentTxns = paymentTransactionRepository.findByPaymentId(payment.id)
            .filter { it.status == COMPLETED }

        // 3. Bank Service에서 대응 Transaction 조회
        paymentTxns.forEach { ptxn ->
            val bankTxn = bankService.getTransaction(ptxn.bankTransactionId)

            // 4. 검증
            require(bankTxn.amount == ptxn.amount) {
                "Amount mismatch: Payment ${payment.paymentId}"
            }
            require(bankTxn.status == "COMPLETED") {
                "Status mismatch: Payment ${payment.paymentId}"
            }
        }
    }
}
```

**Balance 검증**:
```kotlin
fun verifyBalance(accountId: Long) {
    // 1. Account의 현재 잔액
    val account = accountRepository.findById(accountId)
    val currentBalance = account.balance

    // 2. Ledger 엔트리로 재계산
    val ledgers = ledgerRepository.findByAccountId(accountId)
        .sortedBy { it.createdAt }

    var calculatedBalance = BigDecimal.ZERO
    ledgers.forEach { ledger ->
        calculatedBalance += when (ledger.entryType) {
            CREDIT -> ledger.amount  // 입금
            DEBIT -> -ledger.amount  // 출금
        }
    }

    // 3. 검증
    require(currentBalance == calculatedBalance) {
        "Balance mismatch for account $accountId: " +
        "current=$currentBalance, calculated=$calculatedBalance"
    }
}
```

### 5. 모니터링 포인트

#### 핵심 메트릭

**처리량 (Throughput)**:
```
# Prometheus 쿼리
rate(http_server_requests_seconds_count{uri="/api/payments"}[5m])
```

**에러율 (Error Rate)**:
```
# 5분 평균 에러율
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count[5m]))
```

**지연시간 (Latency)**:
```
# P95 레이턴시
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{uri="/api/payments"}[5m])
)
```

**비즈니스 메트릭**:
```kotlin
// 결제 성공률
val totalPayments = paymentRepository.count()
val successfulPayments = paymentRepository.countByStatus(APPROVED)
val successRate = successfulPayments.toDouble() / totalPayments

// 부분 취소율
val cancelledPayments = paymentRepository.countByStatus(PARTIALLY_CANCELLED)
val partialCancelRate = cancelledPayments.toDouble() / successfulPayments
```

#### 알림 규칙

```yaml
# Prometheus AlertManager 설정 예시
groups:
- name: payment_alerts
  rules:
  - alert: HighErrorRate
    expr: |
      sum(rate(http_server_requests_seconds_count{status=~"5..", app="payment-service"}[5m]))
      /
      sum(rate(http_server_requests_seconds_count{app="payment-service"}[5m]))
      > 0.05
    for: 2m
    annotations:
      summary: "Payment Service error rate > 5%"

  - alert: SlowResponse
    expr: |
      histogram_quantile(0.95,
        rate(http_server_requests_seconds_bucket{uri="/api/payments"}[5m])
      ) > 2
    for: 5m
    annotations:
      summary: "Payment API P95 latency > 2s"
```

---

## 주요 시나리오 플로우

### 시나리오 1: 결제 승인 전체 플로우

```
┌─────────┐      ┌─────────────┐      ┌──────────────┐
│ Client  │      │ Payment Svc │      │  Bank Svc    │
└────┬────┘      └──────┬──────┘      └──────┬───────┘
     │                  │                    │
     │ 1. POST /payments│                    │
     │ Idempotency-Key: │                    │
     │   payment-001    │                    │
     ├─────────────────>│                    │
     │                  │                    │
     │                  │ 2. Check idempotency│
     │                  │────┐               │
     │                  │    │ findByIdempotency│
     │                  │<───┘ Key(payment-001)│
     │                  │                    │
     │                  │ 3. Create Payment  │
     │                  │────┐ (PENDING)     │
     │                  │    │               │
     │                  │<───┘               │
     │                  │                    │
     │                  │ 4. Create          │
     │                  │    PaymentTransaction│
     │                  │────┐ (APPROVAL, PENDING)│
     │                  │    │               │
     │                  │<───┘               │
     │                  │                    │
     │                  │ 5. POST /withdraw  │
     │                  │ Idempotency-Key:   │
     │                  │   payment-001-bank │
     │                  ├───────────────────>│
     │                  │                    │
     │                  │                    │ 6. Pessimistic Lock
     │                  │                    │────┐
     │                  │                    │    │ findByIdForUpdate
     │                  │                    │<───┘
     │                  │                    │
     │                  │                    │ 7. Validate & Withdraw
     │                  │                    │────┐
     │                  │                    │    │ balance -= amount
     │                  │                    │<───┘
     │                  │                    │
     │                  │                    │ 8. Create Transaction
     │                  │                    │────┐ + Ledger
     │                  │                    │    │
     │                  │                    │<───┘
     │                  │                    │
     │                  │ 9. Return TXN      │
     │                  │<───────────────────┤
     │                  │                    │
     │                  │ 10. Update PaymentTransaction│
     │                  │─────┐ (COMPLETED) │
     │                  │     │ bankTransactionId set│
     │                  │<────┘              │
     │                  │                    │
     │                  │ 11. Update Payment │
     │                  │─────┐ (APPROVED)  │
     │                  │     │              │
     │                  │<────┘              │
     │                  │                    │
     │ 12. Return Payment│                   │
     │<─────────────────┤                    │
     │                  │                    │
```

**각 단계 설명**:

1. **클라이언트 요청**: Idempotency-Key 포함
2. **멱등성 체크**: 기존 결제 있는지 확인 (없으면 계속)
3. **Payment 생성**: status = PENDING, approvedAmount = totalAmount
4. **PaymentTransaction 생성**: type = APPROVAL, status = PENDING
5. **Bank Service 호출**: 출금 요청 (별도 Idempotency-Key)
6. **계좌 락**: SELECT FOR UPDATE로 동시성 제어
7. **출금 처리**: 잔액 검증 후 차감
8. **이중 기록**: Transaction + Ledger 동시 생성
9. **Bank 응답**: transactionId 반환
10. **PaymentTransaction 업데이트**: COMPLETED + bankTransactionId 저장
11. **Payment 업데이트**: status = APPROVED
12. **클라이언트 응답**: 전체 Payment 객체 반환

---

### 시나리오 2: 부분 취소 플로우

```
초기 상태:
Payment {
  paymentId: "PAY-001",
  totalAmount: 10000,
  approvedAmount: 10000,
  cancelledAmount: 0,
  status: APPROVED
}

Step 1: 첫 부분 취소 (3000원)
───────────────────────────────────
POST /api/payments/PAY-001/cancel
Headers: Idempotency-Key: cancel-001
Body: { "amount": 3000, "reason": "Product A damaged" }

1. Payment 조회: approvedAmount = 10000
2. 검증: 10000 >= 3000 ✓
3. PaymentTransaction 생성: type=CANCEL, amount=3000, status=PENDING
4. Bank Service 호출: deposit(3000) → 환불
5. PaymentTransaction 업데이트: status=COMPLETED, bankTransactionId set
6. Payment 업데이트:
   - approvedAmount: 10000 - 3000 = 7000
   - cancelledAmount: 0 + 3000 = 3000
   - status: PARTIALLY_CANCELLED

결과:
Payment {
  totalAmount: 10000,      # 불변
  approvedAmount: 7000,     # 감소
  cancelledAmount: 3000,    # 증가
  status: PARTIALLY_CANCELLED
}

Step 2: 두 번째 부분 취소 (2000원)
───────────────────────────────────
POST /api/payments/PAY-001/cancel
Headers: Idempotency-Key: cancel-002
Body: { "amount": 2000, "reason": "Product B defective" }

1. Payment 조회: approvedAmount = 7000
2. 검증: 7000 >= 2000 ✓
3. (동일한 프로세스...)
6. Payment 업데이트:
   - approvedAmount: 7000 - 2000 = 5000
   - cancelledAmount: 3000 + 2000 = 5000
   - status: PARTIALLY_CANCELLED (여전히)

결과:
Payment {
  totalAmount: 10000,
  approvedAmount: 5000,
  cancelledAmount: 5000,
  status: PARTIALLY_CANCELLED
}

Step 3: 전액 취소 (5000원)
───────────────────────────────────
POST /api/payments/PAY-001/cancel
Headers: Idempotency-Key: cancel-003
Body: {}  # amount 생략 시 전액

1. Payment 조회: approvedAmount = 5000
2. amount = null → cancelAmount = 5000 (전액)
3. 검증: 5000 >= 5000 ✓
6. Payment 업데이트:
   - approvedAmount: 5000 - 5000 = 0
   - cancelledAmount: 5000 + 5000 = 10000
   - status: FULLY_CANCELLED  ← 상태 변경!

최종 결과:
Payment {
  totalAmount: 10000,      # 불변
  approvedAmount: 0,        # 전액 취소
  cancelledAmount: 10000,   # = totalAmount
  status: FULLY_CANCELLED
}
```

---

## 트러블슈팅 가이드

### 문제 1: "Insufficient balance" 에러

**증상**:
```json
{
  "status": 400,
  "error": "BAD_REQUEST",
  "message": "Account balance is insufficient",
  "path": "/api/accounts/1/withdraw"
}
```

**원인 분석**:
```kotlin
// 1. 현재 잔액 확인
GET /api/accounts/1/balance
// → { "balance": 3000 }

// 2. 최근 거래 내역 조회
GET /api/accounts/1/transactions?size=10
// → 어떤 거래로 잔액이 줄었는지 확인

// 3. Ledger로 검증
SELECT * FROM ledger WHERE account_id = 1 ORDER BY created_at DESC LIMIT 10;
```

**해결**:
- 예상 잔액과 실제 잔액 비교
- 필요 시 입금 후 재시도

---

### 문제 2: "OptimisticLockException" 발생

**증상**:
```
org.springframework.orm.ObjectOptimisticLockingFailureException:
Row was updated or deleted by another transaction
```

**원인**: 동시에 같은 Payment를 취소 시도

**재현**:
```bash
# Terminal 1
curl -X POST .../payments/PAY-001/cancel \
  -H "Idempotency-Key: cancel-1" \
  -d '{"amount": 5000}'

# Terminal 2 (동시에)
curl -X POST .../payments/PAY-001/cancel \
  -H "Idempotency-Key: cancel-2" \
  -d '{"amount": 6000}'
```

**해결**: 클라이언트 재시도
```kotlin
// 자동 재시도 로직 (클라이언트)
fun cancelPaymentWithRetry(paymentId: String, amount: BigDecimal): Payment {
    repeat(3) { attempt ->
        try {
            return paymentService.cancelPayment(paymentId, amount, ...)
        } catch (e: OptimisticLockException) {
            if (attempt == 2) throw e
            Thread.sleep(100 * (attempt + 1))  // Exponential backoff
        }
    }
}
```

---

### 문제 3: Payment는 APPROVED인데 Bank Transaction이 없음

**증상**:
```kotlin
val payment = paymentRepository.findByPaymentId("PAY-001")
// → status = APPROVED

val paymentTxn = paymentTransactionRepository.findByPaymentId(payment.id)
// → bankTransactionId = null ???
```

**원인**: Saga 중간에 실패했지만 보상 로직 누락

**디버깅**:
```sql
-- PaymentTransaction 상태 확인
SELECT id, transaction_id, status, bank_transaction_id, created_at, completed_at
FROM payment_transaction
WHERE payment_id = (SELECT id FROM payment WHERE payment_id = 'PAY-001');

-- 결과:
-- id=1, type=APPROVAL, status=PENDING, bank_transaction_id=null
-- → Bank 호출 전에 실패한 케이스
```

**해결**: 수동 보상
```kotlin
// Payment 상태를 FAILED로 수정
val payment = paymentRepository.findByPaymentId("PAY-001")
payment.status = PaymentStatus.FAILED
paymentRepository.save(payment)

// PaymentTransaction도 FAILED로
val txn = paymentTransactionRepository.findByPaymentId(payment.id)
txn.status = PaymentTransactionStatus.FAILED
paymentTransactionRepository.save(txn)
```

---

### 문제 4: Balance 불일치

**증상**:
```
Account.balance = 50,000원
Ledger 재계산 결과 = 45,000원
```

**진단 스크립트**:
```kotlin
fun diagnoseBalanceMismatch(accountId: Long) {
    val account = accountRepository.findById(accountId)
    println("Current balance: ${account.balance}")

    // Ledger로 재계산
    val ledgers = ledgerRepository.findByAccountId(accountId)
        .sortedBy { it.createdAt }

    var calculatedBalance = BigDecimal.ZERO
    ledgers.forEachIndexed { index, ledger ->
        val before = calculatedBalance
        calculatedBalance += when (ledger.entryType) {
            CREDIT -> ledger.amount
            DEBIT -> -ledger.amount
        }

        println("[$index] ${ledger.entryType} ${ledger.amount}: $before → $calculatedBalance")

        // Ledger 자체의 스냅샷과 비교
        if (ledger.balanceBefore != before) {
            println("  WARNING: Ledger.balanceBefore mismatch!")
        }
        if (ledger.balanceAfter != calculatedBalance) {
            println("  WARNING: Ledger.balanceAfter mismatch!")
        }
    }

    println("Calculated balance: $calculatedBalance")

    if (account.balance != calculatedBalance) {
        println("MISMATCH DETECTED!")
        println("Difference: ${account.balance - calculatedBalance}")
    }
}
```

**복구**:
```kotlin
// Ledger가 정확하다고 가정하고 Account.balance 수정
val correctBalance = calculateBalanceFromLedger(accountId)
account.balance = correctBalance
accountRepository.save(account)
```

---

## 개발 환경 설정

### 1. 사전 요구사항

```bash
# Java 17+
java -version

# Gradle 8+
gradle --version

# Docker & Docker Compose
docker --version
docker-compose --version

# PostgreSQL Client (선택)
psql --version
```

### 2. 로컬 실행

```bash
# 1. PostgreSQL 시작
docker-compose up -d postgres-bank postgres-payment

# 2. Bank Service 실행
./gradlew :bank-service:bootRun
# → http://localhost:8080

# 3. Payment Service 실행 (별도 터미널)
./gradlew :payment-service:bootRun
# → http://localhost:8081

# 4. 모니터링 (선택)
docker-compose up -d prometheus grafana
# → Prometheus: http://localhost:9090
# → Grafana: http://localhost:3000 (admin/admin)
```

### 3. 테스트 실행

```bash
# 전체 테스트
./gradlew test

# 특정 서비스만
./gradlew :bank-service:test
./gradlew :payment-service:test

# 통합 테스트만
./gradlew :bank-service:test --tests "*IntegrationTest"
```

### 4. API 테스트

**HTTP Client 파일 사용**:
```bash
# IntelliJ IDEA에서 api-test.http 열기
# 각 요청 옆의 "Run" 버튼 클릭
```

**cURL 사용**:
```bash
# E2E 테스트 스크립트
./test-simple.sh   # 기본 플로우
./test-e2e.sh      # 확장 플로우
```

### 5. 데이터베이스 접속

```bash
# Bank DB
psql -h localhost -p 5432 -U bank -d bankdb
# Password: bank123

# Payment DB
psql -h localhost -p 5433 -U payment -d paymentdb
# Password: payment123

# 유용한 쿼리
\dt                    # 테이블 목록
\d account             # 테이블 스키마
SELECT * FROM account LIMIT 10;
```

### 6. 로그 확인

```bash
# Bank Service 로그
tail -f bank-service/logs/application.log

# Payment Service 로그
tail -f payment-service/logs/application.log

# Docker 로그
docker-compose logs -f postgres-bank
docker-compose logs -f prometheus
```

---

## 추가 학습 자료

### 문서

- `docs/architecture.md` - 상세 아키텍처 설계 문서 (2100+ lines)
- `docs/tdd-plan.md` - TDD 계획 및 테스트 전략
- `TESTING.md` - 테스트 실행 가이드
- `README.md` - 프로젝트 개요

### 코드 탐색 순서

1. **Entity 먼저** (도메인 이해)
   - `bank-service/domain/Account.kt`
   - `payment-service/domain/Payment.kt`

2. **Repository** (데이터 접근)
   - `bank-service/repository/AccountRepository.kt`
   - `bank-service/repository/TransactionRepository.kt`

3. **Service** (비즈니스 로직)
   - `bank-service/service/TransactionService.kt`
   - `payment-service/service/PaymentService.kt`

4. **Controller** (API)
   - `bank-service/controller/AccountController.kt`
   - `payment-service/controller/PaymentController.kt`

5. **통합 테스트** (실제 사용 예시)
   - `bank-service/integration/BankServiceIntegrationTest.kt`
   - `payment-service/integration/PaymentServiceIntegrationTest.kt`

### Git 히스토리 탐색

```bash
# 주요 커밋 보기
git log --oneline --graph --all

# 특정 파일의 변경 이력
git log --follow -- bank-service/src/main/kotlin/com/bank/payment/bank/service/TransactionService.kt

# 특정 커밋의 변경사항
git show 718776e  # Payment Service 구현 커밋
```

---

## 연락처 및 지원

**질문/이슈**:
- GitHub Issues 활용
- 코드 리뷰 요청 시 PR 생성

**문서 업데이트**:
- 이 문서는 코드 변경 시 함께 업데이트되어야 합니다
- 새로운 기능 추가 시 "주요 시나리오 플로우" 섹션에 추가 권장

---

**마지막 업데이트**: 2024-01-15
**버전**: 1.0.0
