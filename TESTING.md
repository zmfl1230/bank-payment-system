# Testing Guide

Bank & Payment System 테스트 가이드

## 1. Quick Start - 간단한 E2E 테스트

가장 빠르게 시스템을 테스트하는 방법:

```bash
# 1. 데이터베이스 시작
docker-compose up -d postgres-bank postgres-payment

# 2. Bank Service 시작 (터미널 1)
./gradlew :bank-service:bootRun

# 3. Payment Service 시작 (터미널 2)
./gradlew :payment-service:bootRun

# 4. 자동 테스트 실행 (터미널 3)
./test-simple.sh
```

### 테스트 시나리오
`test-simple.sh`는 다음을 자동으로 테스트합니다:
1. ✅ 계좌 생성
2. ✅ 100,000원 입금
3. ✅ 30,000원 결제
4. ✅ 10,000원 부분 취소
5. ✅ 5,000원 추가 부분 취소
6. ✅ 최종 잔액 확인 (85,000원)

## 2. HTTP Client 파일 사용 (권장)

IntelliJ IDEA 또는 VSCode REST Client 확장을 사용하는 경우:

### IntelliJ IDEA
1. `api-test.http` 파일 열기
2. 각 요청 옆의 ▶️ 버튼 클릭
3. 변수가 자동으로 설정됨 (accountId, paymentId)

### VSCode
1. REST Client 확장 설치: https://marketplace.visualstudio.com/items?itemName=humao.rest-client
2. `api-test.http` 파일 열기
3. `Send Request` 링크 클릭

### 포함된 테스트 시나리오
- ✅ 계좌 생성 및 관리
- ✅ 입출금 처리
- ✅ 결제 승인 및 취소
- ✅ 멱등성 검증
- ✅ 에러 시나리오 (잔액 부족, 취소 한도 초과 등)
- ✅ 헬스체크 및 메트릭

## 3. 수동 cURL 테스트

### 기본 흐름

```bash
# 1. 계좌 생성
curl -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -d '{"userId": 1, "accountType": "CHECKING"}'
# Response: {"id": 1, "accountNumber": "...", "balance": 0, ...}

# 2. 입금 (100,000원)
curl -X POST http://localhost:8080/api/accounts/1/deposit \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: deposit-001" \
  -d '{"amount": 100000, "description": "Initial deposit"}'

# 3. 잔액 확인
curl http://localhost:8080/api/accounts/1/balance
# Response: {"accountId": 1, "balance": 100000.00, ...}

# 4. 결제 승인 (30,000원)
curl -X POST http://localhost:8081/api/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: payment-001" \
  -d '{
    "userId": "user-001",
    "accountId": 1,
    "amount": 30000,
    "merchantId": "MERCHANT-001",
    "orderId": "ORDER-001",
    "description": "Product purchase"
  }'
# Response: {"paymentId": "PAY-...", "status": "APPROVED", ...}

# 5. 잔액 확인 (70,000원이어야 함)
curl http://localhost:8080/api/accounts/1/balance

# 6. 부분 취소 (10,000원)
curl -X POST http://localhost:8081/api/payments/PAY-XXX/cancel \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: cancel-001" \
  -d '{"amount": 10000, "reason": "Customer request"}'

# 7. 최종 잔액 확인 (80,000원이어야 함)
curl http://localhost:8080/api/accounts/1/balance
```

## 4. Unit & Integration Tests

```bash
# 전체 테스트 실행 (23개)
./gradlew test

# Bank Service만
./gradlew :bank-service:test

# Payment Service만
./gradlew :payment-service:test

# 특정 테스트 클래스
./gradlew :bank-service:test --tests "BankServiceIntegrationTest"
```

## 5. 모니터링

### Grafana 대시보드
```bash
# Prometheus & Grafana 시작
docker-compose up -d prometheus grafana

# 접속
open http://localhost:3000  # admin/admin
```

대시보드에서 확인 가능한 메트릭:
- Request Rate (요청/초)
- Response Time (응답 시간)
- Error Rate (에러율)
- CPU Usage (CPU 사용률)
- JVM Memory (메모리 사용량)
- Thread Count (스레드 수)

### Health Check
```bash
# Bank Service
curl http://localhost:8080/actuator/health

# Payment Service
curl http://localhost:8081/actuator/health
```

### Prometheus Metrics
```bash
# Bank Service 메트릭
curl http://localhost:8080/actuator/prometheus

# Payment Service 메트릭
curl http://localhost:8081/actuator/prometheus
```

## 6. 에러 시나리오 테스트

### 잔액 부족
```bash
curl -X POST http://localhost:8080/api/accounts/1/withdraw \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: withdraw-fail-001" \
  -d '{"amount": 999999, "description": "Should fail"}'
# Expected: 400 Bad Request
```

### 취소 한도 초과
```bash
# 승인된 금액보다 많은 취소 시도
curl -X POST http://localhost:8081/api/payments/PAY-XXX/cancel \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: cancel-fail-001" \
  -d '{"amount": 999999, "reason": "Should fail"}'
# Expected: 400 Bad Request
```

### 멱등성 검증
```bash
# 동일한 Idempotency-Key로 두 번 요청
curl -X POST http://localhost:8080/api/accounts/1/deposit \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idempotency-test-001" \
  -d '{"amount": 10000, "description": "Test"}'

# 같은 키로 재요청 -> 동일한 결과 반환
curl -X POST http://localhost:8080/api/accounts/1/deposit \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idempotency-test-001" \
  -d '{"amount": 10000, "description": "Test"}'
```

## 7. 트러블슈팅

### 서비스가 시작되지 않을 때
```bash
# 데이터베이스 상태 확인
docker ps | grep -E "bank-db|payment-db"

# 로그 확인
docker logs bank-db
docker logs payment-db

# 데이터베이스 재시작
docker-compose restart postgres-bank postgres-payment
```

### 포트 충돌
```bash
# 8080 포트 사용 중인 프로세스 확인
lsof -ti:8080

# 프로세스 종료
kill $(lsof -ti:8080)
```

### 데이터베이스 초기화
```bash
# 데이터베이스 컨테이너와 볼륨 삭제
docker-compose down -v

# 재시작
docker-compose up -d postgres-bank postgres-payment
```

## 8. 성능 테스트 (Optional)

### Apache Bench 사용
```bash
# 100개 요청, 10개 동시
ab -n 100 -c 10 -H "Content-Type: application/json" \
  http://localhost:8080/api/accounts/1/balance
```

### wrk 사용
```bash
# 10초간 10개 connection으로 부하 테스트
wrk -t10 -c10 -d10s http://localhost:8080/api/accounts/1/balance
```

## 정리

서비스 종료:
```bash
# Gradle 프로세스 종료
pkill -f "bank-service"
pkill -f "payment-service"

# Docker 컨테이너 정지
docker-compose down
```
