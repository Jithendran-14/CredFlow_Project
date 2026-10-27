# CredFlow — Eligibility & Consent/Verification Integration Contracts (v1)

**Status:** Shared implementation specification, **not** a declaration that every endpoint is live.  
**Last revised:** 2026-10-16  
**Audience:** Application Service, Consent & Verification Service, Eligibility Service, mock-provider, Auth/Gateway teams.

> **Contract rule:** An endpoint or JSON shape marked **PROPOSED** must be implemented and tested before callers treat it as available. Existing service authorization applies only where explicitly indicated. Sample identities, financial amounts, dates, and fixture identifiers are synthetic.

## 1. Current implementation versus required APIs

| Capability | Endpoint / mechanism | Status |
|---|---|---|
| Create a loan application | `POST /api/v1/applications` | **IMPLEMENTED / TESTED** |
| Retrieve an application | `GET /api/v1/applications/{applicationId}` | **IMPLEMENTED / TESTED** |
| Include business start date in GET response | Extend application response with `businessProfile` | **PROPOSED / NOT IMPLEMENTED** |
| Enforce ownership on existing GET | JWT-based ADMIN/MARKETPLACE/SELLER authorization | **IMPLEMENTED / TESTED** |
| Ownership check for another service | `POST /internal/v1/applications/{applicationId}/ownership-check` | **PROPOSED / NOT IMPLEMENTED** |
| Retrieve authorised financial data | `GET /internal/v1/applications/{applicationId}/financial-data` | **PROPOSED / NOT IMPLEMENTED** |
| Call verification mock | `POST /mock/v1/verifications/financial-data` | **PROPOSED / NOT IMPLEMENTED** |
| Resolve `sellerFixtureId` | Trusted seller ID -> provider fixture mapping owned by Verification | **PROPOSED / NOT IMPLEMENTED** |
| Publish `application.submitted` to Kafka | Oracle outbox publisher | **NOT IMPLEMENTED**; Oracle outbox persistence verified |
| Machine-to-machine JWT roles/credentials | Dedicated scoped service identities | **NOT IMPLEMENTED** |

**Known deployments:** Application Service `http://localhost:8081`; Auth Service `http://localhost:8085`. Port `8082` for Consent & Verification is **proposed**, not confirmed. Verification mock port is **not assigned**.

## 2. Application details — Application Service

### Existing endpoint (IMPLEMENTED / TESTED)

```http
GET http://localhost:8081/api/v1/applications/{applicationId}
Authorization: Bearer <authorized-user-jwt>
```

**Current response** (`200 OK`):

```json
{
  "applicationId": "6074143c-1d49-469e-bfac-7de127685e83",
  "applicationReference": "CF-3E725AD6D2C34812AB1E",
  "sellerId": "SELLER-1001",
  "marketplaceId": "11111111-1111-4111-8111-111111111111",
  "requestedAmount": "500000.00",
  "currency": "INR",
  "loanPurpose": "WORKING_CAPITAL",
  "status": "AWAITING_CONSENT",
  "applicationRevision": 1,
  "version": 0,
  "workflowVersion": 0
}
```

### Extended application-details response (PROPOSED)

**Application Service must implement this addition.** Keep every existing field and add `businessProfile` with the stored business details, including `businessStartDate`.

```json
{
  "applicationId": "6074143c-1d49-469e-bfac-7de127685e83",
  "applicationReference": "CF-3E725AD6D2C34812AB1E",
  "sellerId": "SELLER-1001",
  "marketplaceId": "11111111-1111-4111-8111-111111111111",
  "requestedAmount": "500000.00",
  "currency": "INR",
  "loanPurpose": "WORKING_CAPITAL",
  "status": "AWAITING_CONSENT",
  "businessProfile": {
    "displayName": "Demo Seller",
    "businessName": "Demo Trading Enterprises",
    "businessStartDate": "2020-01-15"
  },
  "applicationRevision": 1,
  "version": 0,
  "workflowVersion": 0
}
```

- `businessStartDate` is an ISO `YYYY-MM-DD` date. Eligibility may calculate business vintage from it.
- Business names and dates in the sample are synthetic.
- `sellerId` may be `null` when no trusted seller mapping exists.
- No downstream service should directly read the Application Service's Oracle tables.

### Existing access control

| Caller | Result |
|---|---|
| `ADMIN` | Can retrieve any application |
| `MARKETPLACE` | Can retrieve only applications matching JWT `marketplaceId` |
| `SELLER` | Can retrieve only applications linked to trusted JWT `sellerId` |
| Missing/invalid JWT | `401 Unauthorized` |
| Authenticated but not entitled | `403 Forbidden` |

**Service-to-service access is not implemented.** Eligibility and Verification must not reuse an ADMIN token or impersonate a seller; implement distinct authorized service identities / internal access before automated calls are enabled.

## 3. Seller ownership check — Application Service (PROPOSED)

**Owner:** Application Service  
**Endpoint:** `POST /internal/v1/applications/{applicationId}/ownership-check`  
**Caller:** Authenticated, specifically authorized Consent & Verification Service.

Request:

```http
POST /internal/v1/applications/6074143c-1d49-469e-bfac-7de127685e83/ownership-check
Authorization: Bearer <verification-service-token>
Content-Type: application/json
```

```json
{
  "sellerId": "SELLER-1001"
}
```

Positive response (`200 OK`):

```json
{
  "applicationId": "6074143c-1d49-469e-bfac-7de127685e83",
  "sellerId": "SELLER-1001",
  "ownsApplication": true
}
```

Negative response (`200 OK`):

```json
{
  "applicationId": "6074143c-1d49-469e-bfac-7de127685e83",
  "sellerId": "SELLER-2002",
  "ownsApplication": false
}
```

**Implementation rules:**

- Compare the requested seller identity to **stored `CF_APPLICATIONS.SELLER_ID`**, derived by the trusted mapping process.
- Do **not** trust `marketplaceSellerId` or a caller-supplied seller ID as proof of ownership.
- Authorize the calling **service identity** before accepting a `sellerId` to check; don't expose arbitrary ownership enumeration to users.
- Unlinked (`null`) seller IDs must not produce a positive ownership result.
- Proposed errors: `400` bad input, `401` no valid service identity, `403` insufficient service permission, `404` unknown application.

## 4. Authorised financial-data retrieval — Consent & Verification Service (PROPOSED)

**Owner:** Consent & Verification Service  
**Proposed host:** `http://localhost:8082` (must be confirmed by service owner)  
**Endpoint:** `GET /internal/v1/applications/{applicationId}/financial-data`  
**Caller:** Authenticated, specifically authorized Eligibility Service.

Request:

```http
GET /internal/v1/applications/6074143c-1d49-469e-bfac-7de127685e83/financial-data
Authorization: Bearer <eligibility-service-token>
```

Proposed success response (`200 OK`):

```json
{
  "applicationId": "6074143c-1d49-469e-bfac-7de127685e83",
  "sellerId": "SELLER-1001",
  "consent": {
    "status": "ACTIVE",
    "consentId": "CONSENT-DEMO-001",
    "scope": "FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY",
    "authorisedAt": "2026-10-15T09:00:00Z",
    "expiresAt": "2026-11-15T09:00:00Z"
  },
  "financialData": {
    "currency": "INR",
    "periodStart": "2026-04-01",
    "periodEnd": "2026-09-30",
    "monthlyRevenue": [
      {"month": "2026-04", "amount": "420000.00"},
      {"month": "2026-05", "amount": "450000.00"},
      {"month": "2026-06", "amount": "470000.00"},
      {"month": "2026-07", "amount": "440000.00"},
      {"month": "2026-08", "amount": "490000.00"},
      {"month": "2026-09", "amount": "510000.00"}
    ],
    "averageMonthlyRevenue": "463333.33",
    "existingMonthlyDebtObligations": "55000.00"
  },
  "source": {
    "provider": "CREDFLOW_VERIFICATION_MOCK",
    "retrievedAt": "2026-10-15T09:05:00Z"
  }
}
```

**Data/consent rules:**

1. `initialSharingConsent` from application creation covers sharing application information with CredFlow. **It is not automatically financial-data consent.**
2. Require separate consent authorizing financial-data access for loan eligibility; verify scope, current status, expiry, and revocation state for every disclosure.
3. Resolve the trusted application/seller context via the Application Service; do not let the caller prove access by simply supplying a seller ID.
4. Expose only necessary financial aggregates to Eligibility, rather than raw provider data by default.
5. Sample financial figures are synthetic; amounts use decimal strings and currency `INR`.

Proposed errors:

| Scenario | Response |
|---|---|
| Authorized and data ready | `200 OK` |
| Consent missing, expired, revoked, or wrong scope | `403 Forbidden` with explicit application-level error code |
| Unknown application | `404 Not Found` |
| Financial data not ready | `409 Conflict`, code `FINANCIAL_DATA_NOT_READY` |
| Missing/invalid service authentication | `401 Unauthorized` |

## 5. Verification mock API (PROPOSED)

**Owner:** Verification mock (team implementation responsibility)  
**Endpoint:** `POST /mock/v1/verifications/financial-data`  
**Calling service:** Consent & Verification only. **Eligibility does not call the mock directly.**  
**Host/port:** Not yet assigned.

Request:

```http
POST /mock/v1/verifications/financial-data
Authorization: Bearer <authorized-internal-credential>
Content-Type: application/json
```

```json
{
  "requestId": "VER-REQ-10001",
  "sellerFixtureId": "FIXTURE-SELLER-1001",
  "dataScope": "FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY"
}
```

Response (`200 OK`, proposed):

```json
{
  "verificationId": "VERIFICATION-DEMO-001",
  "requestId": "VER-REQ-10001",
  "sellerFixtureId": "FIXTURE-SELLER-1001",
  "status": "SUCCESS",
  "financialData": {
    "currency": "INR",
    "periodStart": "2026-04-01",
    "periodEnd": "2026-09-30",
    "monthlyRevenue": [
      {"month": "2026-04", "amount": "420000.00"},
      {"month": "2026-05", "amount": "450000.00"},
      {"month": "2026-06", "amount": "470000.00"},
      {"month": "2026-07", "amount": "440000.00"},
      {"month": "2026-08", "amount": "490000.00"},
      {"month": "2026-09", "amount": "510000.00"}
    ],
    "existingMonthlyDebtObligations": "55000.00"
  },
  "verifiedAt": "2026-10-15T09:05:00Z"
}
```

**Mock design:** deterministic responses for a specific fixture and scenario; future scenarios for missing data, verification failure, timeout, and retriable provider errors. Require internal authentication if reachable outside a strictly isolated local test environment. Fixture values are examples, not confirmed fixtures.

## 6. Seller IDs versus fixture IDs

Three identifiers have separate meanings:

| Field | Example | Origin / responsibility |
|---|---|---|
| `marketplaceSellerId` | `SELLER-1001` | Marketplace-provided identifier |
| `sellerId` | `SELLER-1001` | CredFlow trusted seller identity, derived using `CF_SELLER_MAPPINGS` |
| `sellerFixtureId` | `FIXTURE-SELLER-1001` | External verification-mock fixture identifier |

The identical value shown for the first two in this demo is coincidental; **do not equate the concepts**.

**Proposed mapping flow:**

```text
applicationId
  -> Application Service verifies application and returns trusted sellerId
  -> Consent & Verification checks ownership and separate financial consent
  -> Consent & Verification resolves sellerId -> sellerFixtureId
  -> Consent & Verification calls mock with sellerFixtureId
  -> Consent & Verification exposes authorised aggregates to Eligibility
```

**Recommended ownership:** Consent & Verification Service stores or loads provider fixture mappings. Application Service should not need provider-specific fixture IDs.

Proposed example fixture mapping:

```json
{
  "sellerId": "SELLER-1001",
  "sellerFixtureId": "FIXTURE-SELLER-1001",
  "provider": "CREDFLOW_VERIFICATION_MOCK"
}
```

The fixture above **has not yet been created or verified**. Do not fabricate the fixture ID by prepending `FIXTURE-` to `sellerId`; use a configured, trusted lookup. For an application whose trusted `sellerId` is `null`, stop with a clear mapping-required status rather than guessing an identity.

## 7. End-to-end target workflow (NOT YET FULLY IMPLEMENTED)

1. **Marketplace -> Application Service:** authenticated marketplace submits `POST /api/v1/applications`.
2. **Application Service -> Oracle outbox:** application and `application.submitted` event are persisted in one transaction. This portion is implemented and was manually verified, including sequential idempotent retry behavior.
3. **Outbox -> Kafka:** pending event is eventually published to topic `application.submitted`. Topic exists, but **the publisher is not implemented yet**.
4. **Consent & Verification Service:** obtains application context, checks seller ownership and appropriate financial-data consent.
5. **Consent & Verification -> mock:** resolves trusted seller ID to provider fixture ID and retrieves synthetic financial data.
6. **Consent & Verification -> Eligibility:** provides only authorised financial data through the proposed internal endpoint.
7. **Eligibility:** uses application `businessStartDate`, requested loan information, and financial aggregates to compute eligibility.

**Operational limitation:** Until the outbox publisher is implemented, downstream services cannot rely on Kafka `application.submitted` delivery. Until service-to-service authentication is implemented, internal endpoints are designs, not callable secure interfaces.

## 8. Team implementation responsibilities

| Owner | Required work |
|---|---|
| **Application Service** | Extend GET response with `businessProfile.businessStartDate`; add authenticated/authorized internal application access and ownership check |
| **Consent & Verification Service** | Financial-consent lifecycle and enforcement; owner checks; seller-fixture mapping; mock calls; authorised financial-data endpoint |
| **Eligibility Service** | Read application details and authorised financial aggregates; business-vintage and eligibility rules |
| **Verification mock** | Synthetic deterministic fixture data and provider failure scenarios |
| **Auth / Gateway** | Narrowly scoped machine-to-machine authorization and secure internal routing; **do not use broad ADMIN-token workarounds** |

### Minimum agreed integration rules

- Validate RS256 JWTs or other explicitly agreed internal service credentials; avoid assuming service roles exist in Auth Service today.
- The existing Auth Service currently has `ADMIN`, `SELLER`, `MARKETPLACE` roles only; dedicated service identities remain to be designed and implemented.
- Use application UUIDs as cross-service identifiers; never expose or depend on Oracle identity primary keys.
- Do not directly access another service's database.
- Do not trust marketplace-supplied identity fields for seller ownership or fixture lookup.
- Financial consent must be purpose-specific, active, unexpired, and unrevoked.
- Money values use decimal strings in example JSON; date-only values use `YYYY-MM-DD`, and timestamps use ISO 8601 UTC.
- Use a versioned `application.submitted` event, with consumer deduplication by `eventId` once publishing is implemented (at-least-once delivery, not exactly-once).

## 9. What can be shared now / implementation gates

**Share now as the v1 target contract:** proposed paths, proposed request/response JSON, access-control principles, ID mapping ownership, and team responsibilities. All teams should keep these names stable unless a coordinated revision is explicitly agreed.

**Implemented now:** marketplace application creation, seller/marketplace/admin-scoped application GET response **without** `businessProfile`, trusted seller mapping, Oracle outbox insertion, sequential request-idempotency behavior; Kafka topic created.

**Not yet implemented / do not claim ready:** business-profile GET extension, service-to-service JWT identities, internal ownership endpoint, financial-data consent API, verification mock API, provider fixture mapping, automated Kafka outbox publisher, and end-to-end integration tests.

**Recommended first implementation:** extend the existing Application GET response with `businessProfile` (including `businessStartDate`) without breaking existing fields. Then secure internal application access and ownership checks; implement consent enforcement, fixture lookup, mock integration, and authorised financial-data retrieval in coordination with Eligibility.
