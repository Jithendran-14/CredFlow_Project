# CredFlow Member 2 final service contracts

Updated 19 October 2026. This document describes the implemented local integration. Passwords, access tokens and private keys are not part of this handoff.

## 1 Eligibility Service

Base URL: http://localhost:8083. Oracle username: CF_ELIGIBILITY.

| Method | Endpoint | Who calls it |
|---|---|---|
| POST | /internal/v1/eligibility/evaluations | orchestration-service with eligibility.evaluate scope |
| GET | /api/v1/operations/{operationId} | Same orchestration client and scope |
| GET | /api/v1/applications/{applicationId}/eligibility | Logged-in seller who owns the application |

Evaluation request:

```json
{
  "evaluationRequestId": "66666666-6666-4666-8666-666666666666",
  "applicationId": "33333333-3333-4333-8333-333333333333",
  "applicationRevision": 1,
  "verificationId": "77777777-7777-4777-8777-777777777777",
  "asOfDate": "2026-10-19",
  "authorisedRetrievalReference": "88888888-8888-4888-8888-888888888888"
}
```

Send Authorization Bearer JWT, X-Correlation-ID and Idempotency-Key equal to evaluationRequestId. HTTP 202 returns operationId, status PENDING and statusUrl. Retrying the same inputs returns the same receipt. Reusing the request ID with different inputs returns 409. Status becomes RUNNING, SUCCEEDED or FAILED; failures include a safe errorCode.

The worker calls Application GET /internal/v1/applications/{id}/context and Consent POST /internal/v1/applications/{id}/financial-data/retrievals with its eligibility-service token and financial-data.read scope. It uses the final 19 October financial response. It does not require an invented consent expiry field. Consent checks live authority; Eligibility checks the attested ACTIVE scope, bound IDs/window and freshness of that authenticated retrieval.

The successful result format remains the previously shared eligibility-result schema: eligibilityResultId, evaluationRequestId, applicationId, applicationRevision, verificationId, financialSnapshotId, calculationVersion, evaluatedAt, asOfDate, requestedAmount, currency, outcome, reasonCodes, financial summary and lenderResults. Amounts are two-decimal strings. Outcomes are EVALUATED or ACTION_REQUIRED. Ineligible products carry their own reasonCodes; ACTION_REQUIRED has no calculated totals or lender results.

GET seller results uses Application ownership-check and the seller-authenticated Orchestration workflow endpoint. It serves the pinned current result, never an arbitrary historical result. HTTP 202 means pending/consent needed. A workflow blocked before assessment returns HTTP 200 with status BLOCKED, workflowStatus, reasonCode and message. Wrong ownership returns 404; wrong role 403; unavailable dependencies 503; stale references 409.

Completion event: topic eligibility.completed, key applicationId. Root schemaVersion 1.0, producer eligibility-service, eventId, applicationId, aggregateVersion, correlationId, occurredAt, actor and optional causationId/originActor. data is the agreed eligibility result. Oracle stores result, outbox and successful operation state in one transaction; publication happens after commit.

## 2 Audit Service

Base URL: http://localhost:8086. Oracle username: CF_AUDIT.

GET /api/v1/applications/{applicationId}/audit requires ADMIN. It returns an array of safe events, ordered by UTC occurrence time and event ID. Other roles cannot use this endpoint.

The consumer handles Application submitted, Consent verification completed and the agreed workflow/lender/offer events. There are 19 supported event types. It validates the 1.0 envelope, producer, Kafka topic and application key. Repeated eventId deliveries do not create duplicate history. It excludes tokens, passwords, retrieval capabilities, identity documents, answers and other unapproved content. Full topic names are listed in backend/audit-service/src/main/resources/application.yml.

## 3 Identity and financial mocks

Base URL: http://localhost:8090. Financial persistence username: CF_SIM_FINANCIAL.

POST /internal/v1/verification/checks keeps the shared four-field request: verificationRequestId, applicationId, applicationRevision and fixtureIdentityId. VERIFICATION_SERVICE role is required. Existing scenarios remain DEMO-VERIFIED-001, DEMO-FAILED-001, DEMO-PENDING-001 and DEMO-ACTION-001. Consent selects the fixture from its trusted seller mapping.

POST /internal/v1/financial-data/retrievals requires client_id consent-verification-service and VERIFICATION_SERVICE role. Send Idempotency-Key equal to requestId.

```json
{
  "requestId": "99999999-9999-4999-8999-999999999999",
  "sellerFixtureId": "DEMO-FINANCIAL-GOOD-001",
  "dataScope": "FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY",
  "asOfDate": "2026-10-19",
  "periodStart": "2026-07-01",
  "periodEndExclusive": "2026-10-01"
}
```

HTTP 200 returns requestId, sellerFixtureId, status SUCCESS and dataset. dataset contains sourceProvider member2-financial-mock, dataReference, dataVersion, retrievedAt, INR currency, periodStart, periodEndExclusive and three ordered months. AVAILABLE rows contain grossSalesAmount, returnsAmount, payoutAmount, orderCount and returnedOrderCount. MISSING rows omit numeric values. The dataset and request receipt are persisted, so retries and restarts preserve the answer. Unknown fixture returns 404; changed input under the same request ID returns 409.

Financial fixtures: DEMO-FINANCIAL-GOOD-001, DEMO-FINANCIAL-LOW-001, DEMO-FINANCIAL-MISSING-001, DEMO-FINANCIAL-ZERO-001 and DEMO-FINANCIAL-HIGH-RETURNS-001. Identity fixtures and financial fixtures are separate mappings.

## 4 Common setup

Custom RS256 JWT, issuer credflow-auth, audience credflow-api and flat roles. Services obtain tokens through Auth POST /oauth2/token using HTTP Basic and form grant_type=client_credentials. Each service receives only its registered permissions. Public PEM files verify tokens; only Auth uses the private key.

Kafka broker: localhost:9092, team Kafka 2.12 distribution version 2.2.0 on Java 8. Microservices target Java 21 and run locally on Java 25. Manual SQL creates tables; JPA manages persistence. Flyway and Keycloak are not used by the active demo.

Public frontend: http://localhost:8080/member2/index.html. Seller result and ADMIN audit timeline use the gateway with the logged-in user's JWT. Internal endpoints stay blocked at the gateway.
