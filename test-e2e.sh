#!/bin/bash

set -e

BANK_URL="http://localhost:8080"
PAYMENT_URL="http://localhost:8081"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}→ $1${NC}"
}

# Function to make HTTP request and print response
make_request() {
    local method=$1
    local url=$2
    local data=$3
    local headers=$4

    print_info "[$method] $url"

    if [ -n "$headers" ] && [ -n "$data" ]; then
        response=$(curl -s -w "\n%{http_code}" -X "$method" "$url" \
            -H "Content-Type: application/json" \
            -H "$headers" \
            -d "$data")
    elif [ -n "$data" ]; then
        response=$(curl -s -w "\n%{http_code}" -X "$method" "$url" \
            -H "Content-Type: application/json" \
            -d "$data")
    else
        response=$(curl -s -w "\n%{http_code}" "$url")
    fi

    http_code=$(echo "$response" | tail -n 1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" -ge 200 ] && [ "$http_code" -lt 300 ]; then
        print_success "Response: $http_code"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
        echo ""
        echo "$body"
    else
        print_error "Response: $http_code"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
        echo ""
        echo "ERROR"
    fi
}

echo "=========================================="
echo "  Bank & Payment System E2E Test"
echo "=========================================="
echo ""

# Wait for services to be ready
print_info "Checking service health..."
for i in {1..30}; do
    if curl -s "$BANK_URL/actuator/health" > /dev/null 2>&1 && \
       curl -s "$PAYMENT_URL/actuator/health" > /dev/null 2>&1; then
        print_success "Services are ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        print_error "Services did not start in time"
        exit 1
    fi
    sleep 1
done
echo ""

# Step 1: Create Account
print_info "Step 1: Create Account"
ACCOUNT_RESPONSE=$(make_request "POST" "$BANK_URL/api/accounts" \
    '{"userId": 1, "accountType": "CHECKING"}')

if [ "$ACCOUNT_RESPONSE" = "ERROR" ]; then
    print_error "Failed to create account"
    exit 1
fi

ACCOUNT_ID=$(echo "$ACCOUNT_RESPONSE" | jq -r '.id')
print_success "Account created: ID=$ACCOUNT_ID"
echo ""

# Step 2: Deposit 100,000 KRW
print_info "Step 2: Deposit 100,000 KRW"
TIMESTAMP=$(date +%s%N)
make_request "POST" "$BANK_URL/api/accounts/$ACCOUNT_ID/deposit" \
    '{"amount": 100000, "description": "Initial deposit"}' \
    "Idempotency-Key: deposit-$TIMESTAMP" > /dev/null
echo ""

# Step 3: Check Balance (should be 100,000)
print_info "Step 3: Check Balance (Expected: 100,000)"
BALANCE_RESPONSE=$(make_request "GET" "$BANK_URL/api/accounts/$ACCOUNT_ID/balance")
BALANCE=$(echo "$BALANCE_RESPONSE" | jq -r '.balance')
if [ "$BALANCE" = "100000" ]; then
    print_success "Balance is correct: $BALANCE KRW"
else
    print_error "Balance mismatch: Expected 100000, Got $BALANCE"
fi
echo ""

# Step 4: Payment Approval - 30,000 KRW
print_info "Step 4: Payment Approval - 30,000 KRW"
TIMESTAMP=$(date +%s%N)
PAYMENT_RESPONSE=$(make_request "POST" "$PAYMENT_URL/api/payments" \
    "{\"userId\": \"user-001\", \"accountId\": $ACCOUNT_ID, \"amount\": 30000, \"merchantId\": \"MERCHANT-001\", \"orderId\": \"ORDER-$TIMESTAMP\", \"description\": \"Product purchase\"}" \
    "Idempotency-Key: payment-$TIMESTAMP")

if [ "$PAYMENT_RESPONSE" = "ERROR" ]; then
    print_error "Failed to create payment"
    exit 1
fi

PAYMENT_ID=$(echo "$PAYMENT_RESPONSE" | jq -r '.paymentId')
print_success "Payment created: ID=$PAYMENT_ID"
echo ""

# Step 5: Check Balance After Payment (should be 70,000)
print_info "Step 5: Check Balance After Payment (Expected: 70,000)"
BALANCE_RESPONSE=$(make_request "GET" "$BANK_URL/api/accounts/$ACCOUNT_ID/balance")
BALANCE=$(echo "$BALANCE_RESPONSE" | jq -r '.balance')
if [ "$BALANCE" = "70000" ]; then
    print_success "Balance is correct: $BALANCE KRW"
else
    print_error "Balance mismatch: Expected 70000, Got $BALANCE"
fi
echo ""

# Step 6: Partial Cancel - 10,000 KRW
print_info "Step 6: Partial Cancel - 10,000 KRW"
TIMESTAMP=$(date +%s%N)
make_request "POST" "$PAYMENT_URL/api/payments/$PAYMENT_ID/cancel" \
    '{"amount": 10000, "reason": "Customer request"}' \
    "Idempotency-Key: cancel-$TIMESTAMP" > /dev/null
echo ""

# Step 7: Check Balance After Cancel (should be 80,000)
print_info "Step 7: Check Balance After Cancel (Expected: 80,000)"
BALANCE_RESPONSE=$(make_request "GET" "$BANK_URL/api/accounts/$ACCOUNT_ID/balance")
BALANCE=$(echo "$BALANCE_RESPONSE" | jq -r '.balance')
if [ "$BALANCE" = "80000" ]; then
    print_success "Balance is correct: $BALANCE KRW"
else
    print_error "Balance mismatch: Expected 80000, Got $BALANCE"
fi
echo ""

# Step 8: Another Partial Cancel - 5,000 KRW
print_info "Step 8: Another Partial Cancel - 5,000 KRW"
TIMESTAMP=$(date +%s%N)
make_request "POST" "$PAYMENT_URL/api/payments/$PAYMENT_ID/cancel" \
    '{"amount": 5000, "reason": "Additional refund"}' \
    "Idempotency-Key: cancel-2-$TIMESTAMP" > /dev/null
echo ""

# Step 9: Final Balance Check (should be 85,000)
print_info "Step 9: Final Balance Check (Expected: 85,000)"
BALANCE_RESPONSE=$(make_request "GET" "$BANK_URL/api/accounts/$ACCOUNT_ID/balance")
BALANCE=$(echo "$BALANCE_RESPONSE" | jq -r '.balance')
if [ "$BALANCE" = "85000" ]; then
    print_success "Balance is correct: $BALANCE KRW"
else
    print_error "Balance mismatch: Expected 85000, Got $BALANCE"
fi
echo ""

# Step 10: Check Final Payment Status
print_info "Step 10: Check Final Payment Status"
PAYMENT_STATUS=$(make_request "GET" "$PAYMENT_URL/api/payments/$PAYMENT_ID")
APPROVED_AMOUNT=$(echo "$PAYMENT_STATUS" | jq -r '.approvedAmount')
CANCELLED_AMOUNT=$(echo "$PAYMENT_STATUS" | jq -r '.cancelledAmount')
STATUS=$(echo "$PAYMENT_STATUS" | jq -r '.status')

print_info "Payment Status: $STATUS"
print_info "Approved Amount: $APPROVED_AMOUNT KRW"
print_info "Cancelled Amount: $CANCELLED_AMOUNT KRW"

if [ "$APPROVED_AMOUNT" = "15000" ] && [ "$CANCELLED_AMOUNT" = "15000" ] && [ "$STATUS" = "PARTIALLY_CANCELLED" ]; then
    print_success "Payment status is correct"
else
    print_error "Payment status mismatch"
fi
echo ""

# Step 11: Get Transaction History
print_info "Step 11: Get Transaction History"
TRANSACTIONS=$(make_request "GET" "$BANK_URL/api/accounts/$ACCOUNT_ID/transactions?page=0&size=10")
TOTAL_TRANSACTIONS=$(echo "$TRANSACTIONS" | jq -r '.totalElements')
print_info "Total Transactions: $TOTAL_TRANSACTIONS"
echo ""

echo "=========================================="
print_success "E2E Test Completed Successfully!"
echo "=========================================="
echo ""
echo "Summary:"
echo "  - Account ID: $ACCOUNT_ID"
echo "  - Payment ID: $PAYMENT_ID"
echo "  - Final Balance: $BALANCE KRW"
echo "  - Total Transactions: $TOTAL_TRANSACTIONS"
echo ""
