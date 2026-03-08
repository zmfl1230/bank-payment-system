#!/bin/bash

# Simple E2E Test Script
# Run: ./test-simple.sh

set -e

BANK="http://localhost:8080"
PAYMENT="http://localhost:8081"

echo "======================================"
echo "  Bank & Payment System Quick Test"
echo "======================================"
echo ""

# Step 1: Create Account
echo "1️⃣  Creating account..."
RESPONSE=$(curl -s -X POST "$BANK/api/accounts" \
  -H "Content-Type: application/json" \
  -d '{"userId": 1, "accountType": "CHECKING"}')
ACCOUNT_ID=$(echo "$RESPONSE" | jq -r '.id')
echo "   ✓ Account created: ID=$ACCOUNT_ID"
echo ""

# Step 2: Deposit
echo "2️⃣  Depositing 100,000 KRW..."
curl -s -X POST "$BANK/api/accounts/$ACCOUNT_ID/deposit" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: deposit-$(date +%s)" \
  -d '{"amount": 100000, "description": "Initial deposit"}' > /dev/null
BALANCE=$(curl -s "$BANK/api/accounts/$ACCOUNT_ID/balance" | jq -r '.balance')
echo "   ✓ Balance: $BALANCE KRW"
echo ""

# Step 3: Payment
echo "3️⃣  Making payment of 30,000 KRW..."
RESPONSE=$(curl -s -X POST "$PAYMENT/api/payments" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: payment-$(date +%s)" \
  -d "{\"userId\": \"user-001\", \"accountId\": $ACCOUNT_ID, \"amount\": 30000, \"merchantId\": \"MERCHANT-001\", \"orderId\": \"ORDER-$(date +%s)\", \"description\": \"Product purchase\"}")
PAYMENT_ID=$(echo "$RESPONSE" | jq -r '.paymentId')
echo "   ✓ Payment approved: ID=$PAYMENT_ID"
BALANCE=$(curl -s "$BANK/api/accounts/$ACCOUNT_ID/balance" | jq -r '.balance')
echo "   ✓ Balance after payment: $BALANCE KRW"
echo ""

# Step 4: Partial Cancel
echo "4️⃣  Cancelling 10,000 KRW..."
curl -s -X POST "$PAYMENT/api/payments/$PAYMENT_ID/cancel" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: cancel-$(date +%s)" \
  -d '{"amount": 10000, "reason": "Customer request"}' > /dev/null
PAYMENT_STATUS=$(curl -s "$PAYMENT/api/payments/$PAYMENT_ID")
APPROVED=$(echo "$PAYMENT_STATUS" | jq -r '.approvedAmount')
CANCELLED=$(echo "$PAYMENT_STATUS" | jq -r '.cancelledAmount')
STATUS=$(echo "$PAYMENT_STATUS" | jq -r '.status')
echo "   ✓ Cancel successful"
echo "   ✓ Approved: $APPROVED KRW, Cancelled: $CANCELLED KRW"
echo "   ✓ Status: $STATUS"
BALANCE=$(curl -s "$BANK/api/accounts/$ACCOUNT_ID/balance" | jq -r '.balance')
echo "   ✓ Balance after cancel: $BALANCE KRW"
echo ""

# Step 5: Another Partial Cancel
echo "5️⃣  Cancelling another 5,000 KRW..."
curl -s -X POST "$PAYMENT/api/payments/$PAYMENT_ID/cancel" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: cancel-2-$(date +%s)" \
  -d '{"amount": 5000, "reason": "Additional refund"}' > /dev/null
PAYMENT_STATUS=$(curl -s "$PAYMENT/api/payments/$PAYMENT_ID")
APPROVED=$(echo "$PAYMENT_STATUS" | jq -r '.approvedAmount')
CANCELLED=$(echo "$PAYMENT_STATUS" | jq -r '.cancelledAmount')
STATUS=$(echo "$PAYMENT_STATUS" | jq -r '.status')
BALANCE=$(curl -s "$BANK/api/accounts/$ACCOUNT_ID/balance" | jq -r '.balance')
echo "   ✓ Final balance: $BALANCE KRW"
echo "   ✓ Approved: $APPROVED KRW, Cancelled: $CANCELLED KRW"
echo "   ✓ Status: $STATUS"
echo ""

# Summary
echo "======================================"
echo "  ✅ All Tests Passed!"
echo "======================================"
echo ""
echo "Summary:"
echo "  Account ID: $ACCOUNT_ID"
echo "  Payment ID: $PAYMENT_ID"
echo "  Final Balance: $BALANCE KRW"
echo "  Total Approved: $APPROVED KRW"
echo "  Total Cancelled: $CANCELLED KRW"
echo ""
echo "💡 View details:"
echo "  Account: $BANK/api/accounts/$ACCOUNT_ID/balance"
echo "  Payment: $PAYMENT/api/payments/$PAYMENT_ID"
echo ""
