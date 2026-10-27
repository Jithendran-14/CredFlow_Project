# CredFlow v1 — Eligibility Service, Audit Service and Verification Mock Contracts

**Document status:** Draft integration contract (16 October 2026)  
**Audience:** Application, Consent and Verification, Orchestration, Gateway and frontend teams.  
**Important:** Internal logic and proposed HTTP APIs are marked separately. Proposed endpoints are not available to call yet.

---

## 1. Eligibility Service

**Status:** Financial import, calculation, Oracle persistence and response formatting implemented and tested. Business HTTP endpoints are proposed.  
**Base URL:** `http://localhost:8083`  
**Oracle schema:** `CF_ELIGIBILITY`

### 1.1 API endpoints

| Method | Endpoint | Intended access | Status |
|---|---|---|---|
| POST | `/internal/v1/eligibility/evaluations` | Orchestration service | Proposed |
| GET | `/api/v1/applications/{applicationId}/eligibility` | Application owner or authorised service | Proposed |

### 1.2 Request an evaluation

**Proposed request:** `POST /internal/v1/eligibility/evaluations`

```http
Authorization: Bearer <orchestration-service-jwt>
Content-Type: application/json
```

**Example request:**

```json
{
  "evaluationRequestId": "66666666-6666-4666-8666-666666666666",
  "applicationId": "33333333-3333-4333-8333-333333333333",
  "applicationRevision": 1,
  "verificationId": "77777777-7777-4777-8777-777777777777",
  "asOfDate": "2026-10-13",
  "authorisedRetrievalReference": "demo-authorised-source-104"
}
```

- `evaluationRequestId` identifies one evaluation attempt.
- `verificationId` is the CredFlow verification record UUID, not the mock provider reference.
- `authorisedRetrievalReference` is a proposed reference for trusted financial retrieval. Its lookup and validation must be agreed with Consent and Verification.
- Eligibility will obtain application and authorised financial details through agreed service APIs. Sellers must not supply figures as already verified data.

**Proposed response:** `202 Accepted`

```json
{
  "operationId": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
  "status": "PENDING",
  "statusUrl": "/api/v1/operations/eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
}
```

The worker and polling endpoint are not implemented. Operation ownership and polling response fields require agreement with Orchestration.

### 1.3 Read the eligibility result

**Proposed request:** `GET /api/v1/applications/{applicationId}/eligibility`

```http
Authorization: Bearer <jwt>
```

**Proposed successful response:** `200 OK`. The response adapter already supports this JSON shape:

```json
{
  "eligibilityResultId": "99999999-9999-4999-8999-999999999999",
  "evaluationRequestId": "66666666-6666-4666-8666-666666666666",
  "applicationId": "33333333-3333-4333-8333-333333333333",
  "applicationRevision": 1,
  "verificationId": "77777777-7777-4777-8777-777777777777",
  "financialSnapshotId": "88888888-8888-4888-8888-888888888888",
  "calculationVersion": "1.0",
  "evaluatedAt": "2026-10-15T09:30:00Z",
  "asOfDate": "2026-10-13",
  "requestedAmount": "500000.00",
  "currency": "INR",
  "outcome": "EVALUATED",
  "reasonCodes": [],
  "averageMonthlyNetSales": "550000.00",
  "averageMonthlyPayout": "530000.00",
  "returnRatePercent": "8.33",
  "lenderResults": [
    {
      "lenderId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1",
      "productId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1",
      "ruleVersion": "1",
      "indicativeMaxAmount": "550000.00",
      "eligible": true,
      "reasonCodes": [],
      "routingPriority": 1
    },
    {
      "lenderId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2",
      "productId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2",
      "ruleVersion": "1",
      "indicativeMaxAmount": "550000.00",
      "eligible": true,
      "reasonCodes": [],
      "routingPriority": 2
    }
  ]
}
```

| Outcome | Meaning |
|---|---|
| EVALUATED | Calculation completed; check each lender's eligible flag. This is not final loan approval. |
| ACTION_REQUIRED | Financial data cannot support a calculation; reasonCodes explains why. |

For ACTION_REQUIRED, `lenderResults` is empty and unknown summary fields are omitted. Financial reason codes are `FINANCIAL_DATA_INCOMPLETE`, `FINANCIAL_DATA_INCONSISTENT` and `FINANCIAL_DATA_INSUFFICIENT`.

**Result and retry rules:**

- Application ownership must be checked before exposing seller results; the ownership API is not integrated yet.
- Match the expected application revision and evaluation reference before returning a current result.
- Reusing an evaluationRequestId with identical inputs returns the saved result internally; changed inputs cause a conflict. HTTP error mapping is pending.
- Orchestration selects the lender/product. Eligibility only supplies preliminary results.
- Example lender/product IDs match local demonstration seeds; align them with Lender Integration before use.

### 1.4 Financial input requirements

Use the **last three complete calendar months**, excluding the month containing `asOfDate`. For 2026-10-13, use July, August and September 2026.

| Required information | Internal field names / format |
|---|---|
| Application context | applicationId, applicationRevision, asOfDate |
| Dataset identity | sourceProvider, dataReference, dataVersion |
| Retrieval timestamp | retrievedAt |
| Currency | INR |
| Monthly period | month as YYYY-MM |
| Coverage | COMPLETE or INCOMPLETE |
| Monthly amounts | grossSales, returnsAmount, payoutAmount |
| Monthly counts | orderCount, returnedOrderCount |

These are internal import names. Mapping to Consent and Verification's final financial response is pending.

Gross sales are before returns. Missing data is not zero. The same dataset identity with unchanged content reuses its snapshot; changed content requires a new data version. Incomplete months or inconsistent returns/counts produce ACTION_REQUIRED handling.

---

## 2. Verification Mock

**Status:** Scenario logic and response formatting implemented and tested; HTTP endpoint proposed.  
**Base URL:** `http://localhost:8090` (shared mock application)

### 2.1 API endpoint

| Method | Endpoint | Intended access | Status |
|---|---|---|---|
| POST | `/internal/v1/verification/checks` | Consent and Verification service | Proposed |

### 2.2 Identity check

**Proposed request:** `POST /internal/v1/verification/checks`

```http
Authorization: Bearer <consent-service-jwt>
Content-Type: application/json
```

```json
{
  "verificationRequestId": "55555555-5555-4555-8555-555555555555",
  "applicationId": "33333333-3333-4333-8333-333333333333",
  "applicationRevision": 1,
  "fixtureIdentityId": "DEMO-VERIFIED-001"
}
```

**Proposed response:** `200 OK`. The implemented response adapter produces this shape:

```json
{
  "verificationRequestId": "55555555-5555-4555-8555-555555555555",
  "applicationId": "33333333-3333-4333-8333-333333333333",
  "applicationRevision": 1,
  "fixtureIdentityId": "DEMO-VERIFIED-001",
  "providerReference": "mock-verification-55555555-5555-4555-8555-555555555555",
  "status": "VERIFIED",
  "message": "Fictional provider response for this demonstration.",
  "checkedAt": "2026-10-15T09:00:00Z"
}
```

### 2.3 Supported scenarios

| fixtureIdentityId | status | reasonCode |
|---|---|---|
| DEMO-VERIFIED-001 | VERIFIED | Omitted |
| DEMO-FAILED-001 | FAILED | IDENTITY_CHECK_FAILED |
| DEMO-PENDING-001 | PENDING | VERIFICATION_PENDING |
| DEMO-ACTION-001 | ACTION_REQUIRED | IDENTITY_INFORMATION_REQUIRED |

- Consent and Verification chooses the fixture through a trusted seller mapping; the seller must not choose it.
- These are fictional identity results. Financial-data retrieval is a separate operation.
- All four provider outcomes are proposed as HTTP 200; PENDING is a provider status.
- Identical request IDs and inputs reuse the cached response. Changed inputs conflict; unknown fixtures are input errors. HTTP error mapping is pending.
- PENDING does not automatically progress. The in-memory cache clears on restart.
- Consent and Verification owns durable verification records. The shared mock's planned Oracle migration is pending; its configuration still uses H2.

---

## 3. Audit Service

**Status:** Audit read endpoint and template application.submitted consumer implemented and tested.  
**Base URL:** `http://localhost:8086`  
**Oracle schema:** `CF_AUDIT`

### 3.1 API endpoint

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| GET | `/api/v1/applications/{applicationId}/audit` | ADMIN | Read stored application event history. |

### 3.2 Read audit history

```http
GET http://localhost:8086/api/v1/applications/{applicationId}/audit
Authorization: Bearer <admin-jwt>
```

**Successful response:** `200 OK`, an array of safe stored event objects ordered by event time and event ID. No records returns `[]`.

| Caller | Outcome |
|---|---|
| ADMIN with valid JWT | 200 OK |
| Authenticated caller without ADMIN | 403 Forbidden |
| Missing or invalid JWT | 401 Unauthorized |

Seller access to an audit timeline is not implemented.

### 3.3 Consumed event contract

**Topic:** `application.submitted`  
**Current supported format:** Template envelope, schemaVersion `1.0`.

| Part | Required fields |
|---|---|
| Envelope | eventId, eventType, schemaVersion, producer, applicationId, aggregateVersion, correlationId, occurredAt, actor, data |
| actor | type, subject; optional organisationId |
| data | sellerId, marketplaceId, requestedAmount, currency, status, applicationRevision, sellerRequestReference |

Current validation expects producer `application-service`, eventType `application.submitted`, UUID seller/marketplace IDs, INR, and status `AWAITING_CONSENT`. Optional envelope fields are originActor and causationId.

**Compatibility pending:** Member 1's flat event with eventVersion and seller IDs such as SELLER-1001 differs from this format. Agree one format before connecting the publisher and consumer.

**Processing rules:** Approved fields are stored; extra fields are discarded. Inbox and history writes share one transaction. Duplicate event IDs do not create another history row; the first accepted event wins. Malformed events go to `application.submitted.DLT`; retryable failures have three retries before dead-letter handling.

---

## 4. Eligibility event contract

**Status:** Proposed; event publication and downstream handling are not implemented.  
**Topic / event type:** `eligibility.completed`

Use an envelope with eventId, eventType, schemaVersion, occurredAt, producer, applicationId, aggregateVersion, correlationId, actor and data. Set schemaVersion to `1.0` and producer to `eligibility-service`. `data` contains the Eligibility result shown in section 1.3; root and data application IDs must match.

The exact common envelope and aggregate-version semantics require agreement with Orchestration. Planned delivery uses a transactional outbox and eventId deduplication. Consumers must match the expected application revision/evaluation before accepting a result.

---

## 5. Shared integration requirements

1. Protected endpoints use `Authorization: Bearer <jwt>`. Member 2 currently uses template Keycloak claim validation; alignment with Member 1's custom RS256 Auth is pending.
2. Agree issuer, audience, public-key/JWKS details, role claim and service permissions before integration.
3. Application/result IDs are UUIDs. Money and percentages in responses are two-decimal strings; dates use YYYY-MM-DD and timestamps use UTC.
4. Member 1 provides application businessStartDate, ownership checks usable by Eligibility, and authorised three-month financial data with dataset references/versions.
5. Member 3 confirms evaluation triggers, expected-result references, polling ownership and shared lender/product IDs.
6. Services use their own Oracle schemas. Do not share private keys, live tokens or database passwords in contracts.

### 5.1 Implementation summary

| Capability | State |
|---|---|
| Financial import, snapshots and eligibility persistence | Implemented and tested |
| Eligibility result response formatting | Implemented and tested |
| Eligibility HTTP endpoints and trusted retrieval integration | Proposed / pending |
| Verification fixture logic and response formatting | Implemented and tested |
| Verification HTTP endpoint | Proposed / pending |
| ADMIN audit endpoint and template submitted-event consumer | Implemented and tested |
| Audit Oracle and temporary Kafka transport checks | Passed |
| Custom Auth alignment and final submitted-event compatibility | Pending |
| Eligibility completion publisher and full frontend integration | Pending |
