# TDD Plan: Bank & Payment System

## Overview

This document defines the test-driven development plan for implementing the Bank and Payment services. Tests will be written before implementation, following the Red-Green-Refactor cycle.

---

## Part 1: Bank Service

### Behaviors

1. **Account Management**: Create and manage user accounts
2. **Deposit Processing**: Add funds to an account with idempotency
3. **Withdrawal Processing**: Remove funds with balance validation and idempotency
4. **Balance Inquiry**: Query current account balance
5. **Transaction History**: Retrieve transaction records
6. **Ledger Recording**: Maintain immutable audit trail

### Test Scenarios

#### Behavior 1: Account Management

**Happy Path**:
- Create new account for existing user
- Account has zero initial balance
- Account is ACTIVE status
- Unique account number is generated

**Edge Cases**:
- Create account for non-existent user (should fail)
- Create multiple accounts for same user (should succeed)

**Error Conditions**:
- Invalid account type
- Null user ID

#### Behavior 2: Deposit Processing

**Happy Path**:
- Deposit positive amount
- Balance increases correctly
- Transaction record created with COMPLETED status
- Ledger entry created as CREDIT
- balance_after matches new account balance

**Edge Cases**:
- Deposit with same idempotency key returns same result
- Concurrent deposits on same account (both succeed)
- Deposit minimum amount (1 KRW)
- Deposit large amount (999,999,999 KRW)

**Error Conditions**:
- Deposit zero amount (should fail)
- Deposit negative amount (should fail)
- Deposit without idempotency key (should fail)
- Deposit to non-existent account (should fail)
- Deposit to FROZEN account (should fail)

**Concurrency Tests**:
- 10 concurrent deposits of 1000 KRW → balance should be 10,000 KRW
- Same idempotency key in concurrent requests → only one succeeds, others return cached result

#### Behavior 3: Withdrawal Processing

**Happy Path**:
- Withdraw amount less than balance
- Balance decreases correctly
- Transaction record created with COMPLETED status
- Ledger entry created as DEBIT
- Reference fields populated (referenceType, referenceId)

**Edge Cases**:
- Withdraw with same idempotency key returns same result
- Withdraw exact balance (balance becomes 0)
- Withdraw after multiple deposits (balance calculation correct)

**Error Conditions**:
- Withdraw more than balance (INSUFFICIENT_BALANCE)
- Withdraw zero amount (should fail)
- Withdraw negative amount (should fail)
- Withdraw without idempotency key (should fail)
- Withdraw from FROZEN account (should fail)

**Concurrency Tests**:
- 2 concurrent withdrawals of 6000 KRW from 10,000 KRW balance → one succeeds, one fails with INSUFFICIENT_BALANCE
- 10 concurrent withdrawals of 1000 KRW from 10,000 KRW balance → exactly 10 succeed
- Concurrent deposit and withdrawal → final balance is correct

**Pessimistic Lock Verification**:
- Verify SELECT FOR UPDATE is used
- Verify lock is held during transaction
- Verify lock is released after commit/rollback

#### Behavior 4: Balance Inquiry

**Happy Path**:
- Get current balance for active account
- Balance matches latest transaction balance_after
- Currency is correct (KRW)

**Edge Cases**:
- Get balance for account with no transactions (returns 0)
- Get balance for account with 1000+ transactions

**Error Conditions**:
- Get balance for non-existent account (should fail)

#### Behavior 5: Transaction History

**Happy Path**:
- Retrieve transactions for account with pagination
- Transactions ordered by created_at DESC
- Filter by transaction type (DEPOSIT, WITHDRAW)
- Filter by date range

**Edge Cases**:
- Get transactions for account with no transactions (empty list)
- Get page beyond available pages (empty list)
- Large page size (1000 items)

**Error Conditions**:
- Invalid date range (start > end)
- Negative page number

#### Behavior 6: Ledger Recording

**Happy Path**:
- Every transaction creates ledger entry
- Ledger is immutable (no updates/deletes)
- balance_before + amount = balance_after (for CREDIT)
- balance_before - amount = balance_after (for DEBIT)

**Edge Cases**:
- Multiple ledger entries for same transaction_id (transfer scenario)
- Ledger entry without corresponding transaction (should not happen)

**Error Conditions**:
- Attempt to update ledger (should be prevented by DB constraints)
- Attempt to delete ledger (should be prevented)

---

## Part 2: Payment Service

### Behaviors

1. **Payment Approval**: Create payment and withdraw from bank account
2. **Partial Cancellation**: Cancel portion of approved amount
3. **Full Cancellation**: Cancel entire approved amount
4. **Multiple Cancellations**: Support multiple partial cancellations
5. **Payment Inquiry**: Retrieve payment details with transaction history
6. **Idempotency**: Prevent duplicate payments

### Test Scenarios

#### Behavior 1: Payment Approval

**Happy Path**:
- Create payment with valid account
- Bank withdrawal succeeds
- Payment status becomes APPROVED
- PaymentTransaction created with type=APPROVAL, status=COMPLETED
- bank_transaction_id is populated
- approved_amount equals total_amount
- cancelled_amount is 0

**Edge Cases**:
- Payment approval with minimum amount (1 KRW)
- Payment approval with large amount
- Payment with custom order_id and merchant_id

**Error Conditions**:
- Payment without idempotency key (should fail)
- Payment with insufficient bank balance (status=FAILED)
- Payment to non-existent account (should fail)
- Payment with zero amount (should fail)
- Payment with negative amount (should fail)

**Integration Tests**:
- Payment approval calls Bank Service /withdraw endpoint
- Bank Service returns transaction_id
- Payment stores bank_transaction_id for reconciliation

**Retry & Timeout Tests**:
- Bank Service timeout → Payment becomes PENDING, retried later
- Bank Service returns 500 → Payment retry with exponential backoff
- Bank Service returns 400 (insufficient balance) → Payment fails immediately (no retry)

**Concurrency Tests**:
- 2 concurrent payments with same idempotency key → only one creates payment
- 2 concurrent payments with different keys → both succeed if balance sufficient

#### Behavior 2: Partial Cancellation

**Happy Path**:
- Cancel 3000 KRW from 10,000 KRW approved payment
- Payment status becomes PARTIALLY_CANCELLED
- approved_amount becomes 7,000 KRW
- cancelled_amount becomes 3,000 KRW
- PaymentTransaction created with type=CANCEL, status=COMPLETED
- Bank deposit succeeds (refund)

**Edge Cases**:
- Cancel exact approved_amount (becomes FULLY_CANCELLED)
- Cancel minimum amount (1 KRW)
- Cancel with reason field

**Error Conditions**:
- Cancel more than approved_amount (should fail)
- Cancel from PENDING payment (should fail)
- Cancel from FAILED payment (should fail)
- Cancel from FULLY_CANCELLED payment (should fail)
- Cancel without idempotency key (should fail)

**Integration Tests**:
- Cancellation calls Bank Service /deposit endpoint
- Bank Service returns transaction_id
- Payment stores bank_transaction_id

#### Behavior 3: Full Cancellation

**Happy Path**:
- Cancel entire approved_amount
- Payment status becomes FULLY_CANCELLED
- approved_amount becomes 0
- cancelled_amount equals total_amount
- PaymentTransaction created with type=CANCEL, status=COMPLETED

**Edge Cases**:
- Cancel by omitting amount (defaults to full cancellation)
- Cancel by specifying amount = approved_amount

#### Behavior 4: Multiple Cancellations

**Happy Path**:
- 10,000 KRW approved payment
- Cancel 3,000 KRW → approved_amount = 7,000, cancelled_amount = 3,000, status = PARTIALLY_CANCELLED
- Cancel 2,000 KRW → approved_amount = 5,000, cancelled_amount = 5,000, status = PARTIALLY_CANCELLED
- Cancel 5,000 KRW → approved_amount = 0, cancelled_amount = 10,000, status = FULLY_CANCELLED
- 3 PaymentTransaction records with type=CANCEL

**Edge Cases**:
- 10 consecutive partial cancellations (1,000 KRW each)
- Cancel after long delay (e.g., 30 days later)

**Error Conditions**:
- Attempt to cancel after FULLY_CANCELLED (should fail)

**Concurrency Tests**:
- 2 concurrent cancellations of 6,000 KRW from 10,000 KRW payment → one succeeds, one fails
- Multiple concurrent cancellations totaling more than approved_amount → some fail

#### Behavior 5: Payment Inquiry

**Happy Path**:
- Get payment by paymentId
- Returns payment with all transactions
- Transactions include approval + cancellations
- Shows current approved_amount and cancelled_amount

**Edge Cases**:
- Get payment with 0 cancellations (only approval transaction)
- Get payment with 10 cancellations

**Error Conditions**:
- Get non-existent payment (should return 404)

#### Behavior 6: Idempotency

**Happy Path**:
- First request with idempotency key creates payment
- Second request with same key returns cached payment (same ID)
- Response is identical

**Edge Cases**:
- Idempotency key expires after 24 hours (new payment can be created)
- Different request body with same key → returns original result (not error)

**Database Tests**:
- Idempotency key unique constraint prevents duplicates
- Concurrent requests with same key → database constraint catches duplicate

**Cache Tests**:
- First request caches response in PAYMENT_IDEMPOTENCY table
- Second request retrieves from cache (no new payment created)
- Cache includes response body and HTTP status

---

## Test Structure

### Bank Service

```
bank-service/src/test/kotlin/com/bank/payment/bank/

Unit Tests:
- domain/
  - AccountTest.kt
    - test_account_creation_with_valid_user
    - test_account_has_zero_initial_balance
    - test_account_number_is_unique

  - TransactionTest.kt
    - test_deposit_increases_balance
    - test_withdraw_decreases_balance
    - test_withdraw_fails_when_insufficient_balance

  - LedgerTest.kt
    - test_ledger_entry_creation
    - test_ledger_balance_calculation_for_credit
    - test_ledger_balance_calculation_for_debit

- service/
  - AccountServiceTest.kt
    - test_create_account_success
    - test_create_account_for_nonexistent_user_fails
    - test_create_multiple_accounts_for_same_user

  - TransactionServiceTest.kt
    - test_deposit_success
    - test_deposit_with_idempotency_key_returns_same_result
    - test_deposit_zero_amount_fails
    - test_deposit_to_frozen_account_fails
    - test_withdraw_success
    - test_withdraw_insufficient_balance_fails
    - test_withdraw_with_pessimistic_lock

  - LedgerServiceTest.kt
    - test_ledger_created_for_deposit
    - test_ledger_created_for_withdrawal
    - test_ledger_is_immutable

Integration Tests:
- integration/
  - AccountIntegrationTest.kt
    - test_account_persistence
    - test_account_retrieval

  - TransactionIntegrationTest.kt
    - test_deposit_flow_end_to_end
    - test_withdraw_flow_end_to_end
    - test_concurrent_deposits_on_same_account
    - test_concurrent_withdrawals_with_pessimistic_lock
    - test_deposit_and_withdraw_concurrent_operations

  - IdempotencyIntegrationTest.kt
    - test_duplicate_deposit_with_same_idempotency_key
    - test_duplicate_withdraw_with_same_idempotency_key
    - test_concurrent_requests_with_same_idempotency_key

API Tests:
- api/
  - AccountApiTest.kt
    - test_post_create_account_returns_201
    - test_get_account_balance_returns_200

  - TransactionApiTest.kt
    - test_post_deposit_returns_200
    - test_post_withdraw_returns_200
    - test_post_withdraw_insufficient_balance_returns_400
    - test_get_transaction_history_returns_200
```

### Payment Service

```
payment-service/src/test/kotlin/com/bank/payment/payment/

Unit Tests:
- domain/
  - PaymentTest.kt
    - test_payment_creation
    - test_payment_approval
    - test_payment_partial_cancellation
    - test_payment_full_cancellation
    - test_payment_status_transitions

  - PaymentTransactionTest.kt
    - test_approval_transaction_creation
    - test_cancel_transaction_creation

- service/
  - PaymentServiceTest.kt
    - test_approve_payment_success
    - test_approve_payment_insufficient_balance_fails
    - test_approve_payment_with_idempotency_key
    - test_cancel_payment_partial_success
    - test_cancel_payment_full_success
    - test_cancel_payment_exceeds_approved_amount_fails
    - test_multiple_partial_cancellations

  - IdempotencyServiceTest.kt
    - test_idempotency_check_cache_hit
    - test_idempotency_check_cache_miss
    - test_idempotency_cache_expiration

Integration Tests:
- integration/
  - PaymentIntegrationTest.kt
    - test_payment_approval_end_to_end
    - test_payment_cancellation_end_to_end
    - test_multiple_cancellations_end_to_end
    - test_concurrent_payments_with_same_idempotency_key
    - test_concurrent_cancellations_on_same_payment

  - BankServiceIntegrationTest.kt
    - test_payment_calls_bank_withdraw
    - test_cancellation_calls_bank_deposit
    - test_bank_service_timeout_handling
    - test_bank_service_error_handling

  - ReconciliationIntegrationTest.kt
    - test_payment_transaction_links_to_bank_transaction
    - test_reconciliation_data_consistency

API Tests:
- api/
  - PaymentApiTest.kt
    - test_post_create_payment_returns_201
    - test_post_cancel_payment_returns_200
    - test_get_payment_returns_200
    - test_post_payment_without_idempotency_key_returns_400
```

---

## Implementation Order

### Phase 1: Bank Service Core (TDD)

#### Step 1: Domain Entities
**Tests**: AccountTest, TransactionTest, LedgerTest
**Implementation**:
- User entity
- Account entity with balance field
- Transaction entity
- Ledger entity

#### Step 2: Account Management
**Tests**: AccountServiceTest, AccountIntegrationTest
**Implementation**:
- AccountService.createAccount()
- AccountRepository
- User validation
- Account number generation

#### Step 3: Deposit Processing
**Tests**: TransactionServiceTest (deposit tests), TransactionIntegrationTest
**Implementation**:
- TransactionService.deposit()
- Balance update logic
- Transaction creation
- Ledger creation
- Idempotency check (DB-based)

#### Step 4: Withdrawal Processing
**Tests**: TransactionServiceTest (withdraw tests), TransactionIntegrationTest (concurrency tests)
**Implementation**:
- TransactionService.withdraw()
- Balance validation
- Pessimistic locking (SELECT FOR UPDATE)
- Transaction creation
- Ledger creation
- Idempotency check

#### Step 5: Query Operations
**Tests**: AccountServiceTest (balance), TransactionServiceTest (history)
**Implementation**:
- AccountService.getBalance()
- TransactionService.getHistory() with pagination
- Date range filtering

#### Step 6: REST API Layer
**Tests**: AccountApiTest, TransactionApiTest
**Implementation**:
- AccountController
- TransactionController
- Request/Response DTOs
- Error handling
- Idempotency-Key header validation

### Phase 2: Payment Service Core (TDD)

#### Step 7: Domain Entities
**Tests**: PaymentTest, PaymentTransactionTest
**Implementation**:
- Payment entity
- PaymentTransaction entity
- Status enum (PENDING, APPROVED, PARTIALLY_CANCELLED, FULLY_CANCELLED, FAILED)

#### Step 8: Bank Service Client
**Tests**: BankServiceIntegrationTest (with MockWebServer)
**Implementation**:
- BankServiceClient (WebClient)
- withdraw() method
- deposit() method
- Timeout configuration
- Error handling

#### Step 9: Payment Approval
**Tests**: PaymentServiceTest (approval tests), PaymentIntegrationTest
**Implementation**:
- PaymentService.approvePayment()
- Call Bank Service withdraw
- Create Payment entity
- Create PaymentTransaction (APPROVAL)
- Update status to APPROVED
- Handle Bank Service errors (PENDING status)

#### Step 10: Payment Cancellation
**Tests**: PaymentServiceTest (cancel tests), PaymentIntegrationTest (multiple cancellations)
**Implementation**:
- PaymentService.cancelPayment()
- Validate approved_amount >= cancel_amount
- Call Bank Service deposit (refund)
- Create PaymentTransaction (CANCEL)
- Update approved_amount and cancelled_amount
- Update status (PARTIALLY_CANCELLED or FULLY_CANCELLED)
- Pessimistic lock on Payment (SELECT FOR UPDATE)

#### Step 11: Idempotency Layer
**Tests**: IdempotencyServiceTest, IdempotencyIntegrationTest
**Implementation**:
- PaymentIdempotency entity
- IdempotencyService
- Request hash generation
- Cache response in DB
- Retrieve cached response
- TTL expiration logic

#### Step 12: Payment Query
**Tests**: PaymentServiceTest (inquiry), PaymentIntegrationTest
**Implementation**:
- PaymentService.getPaymentById()
- Fetch payment with transactions (JOIN FETCH)
- Return DTO with all transaction history

#### Step 13: REST API Layer
**Tests**: PaymentApiTest
**Implementation**:
- PaymentController
- Request/Response DTOs
- Idempotency-Key header handling
- Error responses

### Phase 3: Integration & End-to-End

#### Step 14: Cross-Service Integration
**Tests**: E2E tests in separate module
**Implementation**:
- Start both services
- Test full payment flow: create account → deposit → payment approval
- Test cancellation flow: payment → partial cancel → partial cancel → full cancel
- Test error scenarios: insufficient balance, service timeout

#### Step 15: Concurrency & Performance
**Tests**: Load tests with JMeter or Gatling
**Implementation**:
- Test concurrent payments
- Test concurrent cancellations
- Measure TPS (transactions per second)
- Verify no deadlocks
- Verify pessimistic locks work correctly

#### Step 16: Reconciliation
**Tests**: ReconciliationIntegrationTest
**Implementation**:
- ReconciliationService (in Payment Service or separate)
- Daily reconciliation batch job
- Transaction matching logic
- Discrepancy reporting

---

## Testing Checklist

For each test:
- [ ] Clear test name describing scenario
- [ ] Arrange: Setup test data
- [ ] Act: Execute the operation
- [ ] Assert: Verify expected outcome
- [ ] Cleanup: Rollback transaction or clear test data

For integration tests:
- [ ] Use @SpringBootTest
- [ ] Use @Transactional for automatic rollback
- [ ] Mock external services (BankServiceClient in Payment tests)
- [ ] Use embedded H2 database for fast tests

For concurrency tests:
- [ ] Use ExecutorService with multiple threads
- [ ] Use CountDownLatch for synchronized start
- [ ] Verify final state is correct
- [ ] Verify no race conditions

For API tests:
- [ ] Use MockMvc or RestAssured
- [ ] Test HTTP status codes
- [ ] Test response body structure
- [ ] Test error responses
- [ ] Test header validation (Idempotency-Key)

---

## Edge Cases Checklist

### Backend-Specific
- [x] Concurrent requests (deposits, withdrawals, payments, cancellations)
- [x] Partial failure (Bank Service fails after Payment created)
- [x] Retry behavior (Bank Service timeout)
- [x] Timeout handling (Circuit breaker not in Phase 1)
- [x] Transaction rollback (Saga pattern for distributed transactions)
- [x] Data inconsistency (Reconciliation tests)
- [x] Idempotency (duplicate requests)
- [x] Pessimistic locking (SELECT FOR UPDATE)
- [x] Optimistic locking (version field) - Can be added later
- [x] Database constraints (unique idempotency_key)

### Payment-Specific
- [x] Payment idempotency (same key returns same payment)
- [x] Cancellation atomicity (Payment + Bank deposit together)
- [x] Async processing order (Retry failed payments in background)
- [x] Retry backoff (Exponential backoff for Bank Service errors)
- [x] Lock contention (Multiple cancellations on same payment)

---

## Test Execution Strategy

### Development Cycle (Red-Green-Refactor)
1. **Red**: Write failing test
2. **Green**: Write minimal code to pass test
3. **Refactor**: Improve code while keeping tests green

### Test Order
1. Unit tests first (fast feedback)
2. Integration tests second (database interactions)
3. API tests third (end-to-end request/response)
4. Load tests last (performance validation)

### Continuous Integration
- All tests run on every commit
- Unit tests: < 10 seconds
- Integration tests: < 1 minute
- API tests: < 2 minutes
- Coverage target: > 80%

---

## Success Criteria

### Bank Service
- [x] All account operations have tests
- [x] Deposit and withdrawal have concurrency tests
- [x] Idempotency is tested with duplicate requests
- [x] Pessimistic locking is verified
- [x] API tests cover all endpoints
- [x] Integration tests cover database operations

### Payment Service
- [x] Payment approval flow is tested end-to-end
- [x] Partial and full cancellation are tested
- [x] Multiple cancellations scenario is tested
- [x] Idempotency is tested at API and service layer
- [x] Bank Service integration is tested with mocks
- [x] Concurrency tests for payments and cancellations

### Overall
- [x] All tests are deterministic (no flaky tests)
- [x] All tests clean up resources (no test pollution)
- [x] Test execution is fast (< 5 minutes total)
- [x] Code coverage > 80%

---

## Next Steps

After TDD planning:
1. Start with Bank Service implementation (Phase 1)
2. Follow Red-Green-Refactor cycle
3. Run tests continuously
4. Move to Payment Service (Phase 2) after Bank Service is complete
5. Add integration tests (Phase 3)
6. Performance and load tests
7. Reconciliation implementation

**First Test to Write**: `AccountTest.test_account_creation_with_valid_user`
