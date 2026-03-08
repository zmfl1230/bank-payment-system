# Bank Payment System

Bank & Payment 시스템 - Spring Boot + Kotlin 기반 분산 결제 시스템

## 📋 프로젝트 개요

멀티모듈 구조의 은행 및 결제 시스템으로, TDD 방식으로 개발되었으며 분산 환경, 동시성, 멱등성을 고려한 설계입니다.

### 주요 기능
- ✅ **Bank Service**: 계좌 관리, 입출금, 원장 기록
- ✅ **Payment Service**: 결제 승인, 부분/전체 취소, 조회
- ✅ **멱등성 보장**: Idempotency-Key 기반 중복 방지
- ✅ **동시성 제어**: Pessimistic Locking + Optimistic Locking
- ✅ **대사 지원**: Bank ↔ Payment 거래 매칭

## 🏗️ 프로젝트 구조

```
bank-payment-system/
├── bank-service/       # 은행 서비스 (계좌 관리, 입출금, 원장)
├── payment-service/    # 결제 서비스 (승인, 취소, 조회)
├── common/             # 공통 모듈
├── docker/             # Docker 구성 (PostgreSQL, Prometheus, Grafana)
└── docs/               # 설계 문서
    ├── architecture.md # 아키텍처 설계 (55KB)
    └── tdd-plan.md     # TDD 계획
```

## 🛠️ 기술 스택

- **Language**: Kotlin 1.9.24
- **Framework**: Spring Boot 3.3.0
- **Database**: PostgreSQL 16 (개발: H2)
- **Build Tool**: Gradle (멀티모듈)
- **Testing**: JUnit 5, MockK, AssertJ
- **Monitoring**: Prometheus + Grafana
- **Code Quality**: ktlint

## 🚀 Quick Start

### 1. 데이터베이스 실행

```bash
docker-compose up -d postgres-bank postgres-payment
```

### 2. Bank 서비스 실행 (포트 8080)

```bash
./gradlew :bank-service:bootRun
```

### 3. Payment 서비스 실행 (포트 8081)

```bash
./gradlew :payment-service:bootRun
```

### 4. 샘플 시나리오 테스트

#### 4.1 사용자 계좌 생성
```bash
curl -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "accountType": "CHECKING"
  }'
```

#### 4.2 계좌 입금 (10,000원)
```bash
curl -X POST http://localhost:8080/api/accounts/1/deposit \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: deposit-001" \
  -d '{
    "amount": 10000,
    "description": "Initial deposit"
  }'
```

#### 4.3 결제 승인 (3,000원)
```bash
curl -X POST http://localhost:8081/api/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: payment-001" \
  -d '{
    "userId": "user-1",
    "accountId": 1,
    "amount": 3000,
    "description": "Product purchase"
  }'
```

#### 4.4 부분 취소 (1,000원)
```bash
curl -X POST http://localhost:8081/api/payments/PAY-ABC123/cancel \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: cancel-001" \
  -d '{
    "amount": 1000,
    "reason": "Customer request"
  }'
```

#### 4.5 잔액 조회
```bash
curl http://localhost:8080/api/accounts/1/balance
```

## 🧪 테스트

### 전체 테스트 실행
```bash
./gradlew test
```

### Bank Service 테스트
```bash
./gradlew :bank-service:test
```

**테스트 통계**: 13개 테스트 (모두 통과)

## 📊 모니터링

### 서비스 엔드포인트
- **Bank Service**: http://localhost:8080
- **Payment Service**: http://localhost:8081
- **Bank Actuator**: http://localhost:8080/actuator
- **Payment Actuator**: http://localhost:8081/actuator

### 모니터링 대시보드
```bash
docker-compose up -d prometheus grafana
```

- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000

## 📖 문서

- [Architecture 설계](docs/architecture.md) - 55KB 상세 설계 문서
- [TDD 계획](docs/tdd-plan.md) - 테스트 시나리오 및 구현 순서

## 🔑 핵심 설계 원칙

### 1. 멱등성 (Idempotency)
- 모든 변경 작업에 Idempotency-Key 필수
- DB Unique Constraint로 중복 방지
- 동일 키로 재요청 시 캐시된 결과 반환

### 2. 동시성 제어 (Concurrency Control)
- **Pessimistic Lock**: 출금 시 SELECT FOR UPDATE
- **Optimistic Lock**: Account.version 필드

### 3. 데이터 정합성 (Data Consistency)
- **Dual Recording**: Transaction + Ledger 이중 기록
- **Balance Snapshot**: 모든 거래에 balance_after 저장
- **대사 지원**: bank_transaction_id로 매칭

### 4. 부분 취소 (Partial Cancellation)
- approved_amount 추적으로 잔여 취소 가능 금액 관리
- 여러 번 부분 취소 가능
- 상태: APPROVED → PARTIALLY_CANCELLED → FULLY_CANCELLED

## 🗂️ Database Schema

### Bank Service Tables
- `users`: 사용자 정보
- `account`: 계좌 (balance, version)
- `transaction`: 거래 내역
- `ledger`: 원장 (불변)

### Payment Service Tables
- `payment`: 결제 집계 (approved_amount, cancelled_amount)
- `payment_transaction`: 개별 승인/취소 거래

## 📈 개발 진행 상황

- ✅ 프로젝트 초기 구성
- ✅ Architecture 설계 문서
- ✅ TDD 계획 수립
- ✅ Bank Service 완성 (Domain/Service/API/Tests)
- ✅ Payment Service 완성 (Domain/Service/API)
- ✅ Database Schema SQL

## 📝 License

This project is for educational purposes.
