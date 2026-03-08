# Bank Payment System

Bank & Payment 시스템 (Spring Boot + Kotlin)

## 프로젝트 구조

```
bank-payment-system/
├── bank-service/       # 은행 서비스 (계좌 관리, 입출금, 원장)
├── payment-service/    # 결제 서비스 (승인, 취소, 조회)
├── common/             # 공통 모듈
└── docker/             # Docker 구성 (PostgreSQL, Prometheus, Grafana)
```

## 기술 스택

- Kotlin 1.9.24
- Spring Boot 3.3.0
- PostgreSQL
- Gradle (멀티모듈)
- ktlint
- Prometheus + Grafana

## 로컬 실행

### 1. 데이터베이스 실행

```bash
docker-compose up -d postgres
```

### 2. Bank 서비스 실행

```bash
./gradlew :bank-service:bootRun
```

### 3. Payment 서비스 실행

```bash
./gradlew :payment-service:bootRun
```

## 개발

### 빌드

```bash
./gradlew build
```

### 테스트

```bash
./gradlew test
```

### Lint

```bash
./gradlew ktlintCheck
./gradlew ktlintFormat
```

## 모니터링

- Bank Service: http://localhost:8080/actuator
- Payment Service: http://localhost:8081/actuator
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000

## API 문서

- [Bank API](docs/bank-api.md)
- [Payment API](docs/payment-api.md)
