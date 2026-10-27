# Member 3 data dictionary — contract version 1.0

This file supplements the four service OpenAPI specifications and combined lender reference and the 21 full event schemas. It aligns the corrected development baseline with the two upstream contracts dated 2026-10-16, preserving their proposed/unimplemented status and the user's Oracle-only storage choice. No frontend is added. All sample identities and documents are fictional.

`R` means required, `O` optional, `C` conditionally required. A UUID is serialized as a string. Dates use ISO `YYYY-MM-DD`; timestamps are UTC ISO 8601 instants. Decimal money is a string with exactly two decimal places and at most 17 integer digits; currency is `INR`. Percentage is a decimal string from 0 through 100, with at most four fractional digits. JSON numbers are used for counts, priorities and versions. Optional values are omitted rather than sent as null UUIDs. Upstream application sellerId is explicitly a nullable trusted string; it is not a UUID. Resource identifiers are references and never cross-service database foreign keys.

## Identity, event envelope and ownership

| Object/field | Type and obligation | Meaning/owner |
|---|---|---|
| Actor.type, subject | enum R, string(255) R | HUMAN or SYSTEM_CLIENT; SYSTEM is accepted for compatibility. Identity is derived from trusted authentication. |
| Actor.organisationId | UUID O | Authenticated marketplace/lender organization. Omitted for unbound system actors. |
| Event.eventId | UUID R | Immutable event identity, reused on every retry. |
| Event.eventType, schemaVersion | string R, constant `1.0` R | Topic and contract version. |
| Event.occurredAt, producer | timestamp R, string R | Emission time and emitting service. Producer is distinct from actor. |
| Event.applicationId | UUID R | Kafka message key and workflow aggregate binding. |
| Event.aggregateVersion | integer >= 1 R | Producer-local aggregate ordering. It is not a global cross-service sequence. |
| Event.correlationId | string matching `[A-Za-z0-9._:-]{1,100}` R | One trace across HTTP, durable jobs, callbacks and events. |
| Event.causationId | UUID O | Immediate initiating event or command when known. |
| Event.data, actor, originActor | object R, Actor R, Actor O | Business payload; immediate actor; verified original actor provenance. |
| JWT sub, roles/role | subject R and configured flat role claim | Default Member 1 custom Auth mode; collections or whitespace/comma-separated roles. KEYCLOAK resource_access mode is explicit opt-in. |
| JWT sellerId | trusted string C | Must match stored sellerId for seller access; SELLER-1001 is valid. Legacy seller_id alias only if camelCase absent. |
| JWT lenderId | UUID C | Assigned lender-officer access; legacy lender_id accepted only if camelCase absent. |
| JWT marketplaceId | UUID C | MARKETPLACE / MARKETPLACE_OPERATIONS scope; legacy marketplace_id only if camelCase absent. |
| JWT aud, iss, exp | validated claims R | Explicit issuer/audience, expiry and RS256 signature using configured public PEM or JWKS. No guessed discovery URL. |
| JWT azp/client_id, scope/scp | named client and scopes C | Machine routes require designated clients and narrow scopes; ADMIN is not a bypass. |


Actors in request JSON are never authorization credentials. Public end-user operations derive provenance server-side. Internal calls are restricted to the designated machine client. ADMIN operational replay requires `ops.replay`; marketplace operations cannot replay another organization's tasks.

## Application-owned input and records (Member 1 boundary)

| Object/fields | Type and obligation |
|---|---|
| Trusted seller identity | sellerId string(120) or null; populated by Application trusted seller mapping. It is distinct from marketplaceSellerId and sellerFixtureId. Null blocks automated progression with TRUSTED_SELLER_MAPPING_REQUIRED. |
| Current Application GET | applicationId UUID R; applicationReference string R; sellerId nullable string; marketplaceId UUID R; requestedAmount positive money R; currency INR R; loanPurpose string R; status R; applicationRevision positive integer R; version/workflowVersion nonnegative integers R. |
| Application GET extension | businessProfile with displayName, businessName and businessStartDate date; proposed and not currently implemented in Member 1. No direct database read or ADMIN-token workaround is introduced. |
| application.submitted.data | Proposed Member 3 canonical envelope: sellerId nullable string R; marketplaceId UUID R; applicationRevision positive integer R; requestedAmount positive money R; currency INR R. Optional richer fields: loanPurpose, businessProfile, marketplaceSellerId, legacy sellerSubject, sellerRequestReference, status AWAITING_CONSENT. Missing business name/purpose blocks with APPLICATION_DETAILS_REQUIRED. |
| application.revised.data | Proposed operationId UUID R; applicationRevision positive integer R; requestedAmount/currency/loanPurpose/businessProfile R. Immutable seller/marketplace binding comes from the original workflow. |

The actual Member 1 flat application event shape was not supplied; only eventVersion and the incompatibility were described. The current Member 2 Audit template instead requires an envelope and UUID sellerId. The canonical Member 3 schema is a proposed bridge that requires publisher/consumer agreement. Do not invent a flat parser or coerce trusted string seller IDs to UUIDs. Application owns corrections and guarded revision publication. Orchestration never edits its Oracle schema.

## Consent and verification (Member 1 boundary)

| Object | Fields |
|---|---|
| CredFlowConsent | consentId UUID R; applicationId UUID in resource/envelope R; consentVersion positive integer R; scopes array R; sourceMarketplaceIds UUID array R; recipientLenderIds UUID array R (named recipients for sharing); purpose string R; noticeVersion string canonical R; status GRANTED/DECLINED/REVOKED R; validityPolicy APPLICATION_OPEN R; grantedAt timestamp C; revokedAt timestamp C. |
| Consent operation | operationId UUID R; consent payload R; originActor Actor O. Internal synchronous result `{operationId,status:APPLIED,workflow}`; queued ownership remains in Consent until APPLIED is confirmed. |
| Verification request | verificationId UUID R; applicationId UUID R; applicationRevision positive integer R; consentId UUID R; consentVersion positive integer R; originActor/trace metadata O. Sent to the configured `credflow.paths.consent-verification-trigger` path (default `/api/v1/internal/verifications`), a proposed private Member 1 boundary, not the provider identity endpoint. |
| Verification result | verificationId UUID R; applicationRevision positive integer R; status VERIFIED/FAILED/PENDING/ACTION_REQUIRED R; checkedAt timestamp R; fixtureIdentityId string R; reasonCode string C for FAILED/ACTION_REQUIRED; providerReference string O; authorisedRetrievalReference trusted string(255) C for VERIFIED progression. applicationId is bound in envelope. The retrieval reference is a proposed durable Consent extension, not providerReference; absent reference yields FINANCIAL_RETRIEVAL_REFERENCE_REQUIRED and no Eligibility job. |

Member 3 proposed workflow consent scopes are MARKETPLACE_DATA_READ, IDENTITY_VERIFICATION and LENDER_APPLICATION_SHARE. The separately proposed upstream financial consent is FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY, ACTIVE/unexpired/unrevoked. Its ownership and exact mapping must be implemented by Consent and Verification; these two contracts are not silently equated. Valid consent additionally requires the current marketplace source, purpose, application-open policy and selected recipient. Initial marketplace sharing evidence is separate. Revocation, dispatch authorization and offer acceptance are serialized at Orchestration. A network request authorized immediately before withdrawal may already have shared data; cancellation/reconciliation records that fact instead of claiming a distributed rollback.

## Financial snapshot and eligibility (Member 2 boundary)

| Object | Fields |
|---|---|
| Financial import (internal Member 2 names) | applicationId/applicationRevision/asOfDate; sourceProvider/dataReference/dataVersion/retrievedAt; currency INR; months with month YYYY-MM, coverage COMPLETE/INCOMPLETE, grossSales/returnsAmount/payoutAmount money, orderCount/returnedOrderCount counts. Consent response mapping remains pending. |
| Evaluation request | Exact POST `/internal/v1/eligibility/evaluations`: evaluationRequestId/applicationId/verificationId UUIDs R; applicationRevision positive integer R; asOfDate date R; authorisedRetrievalReference trusted string(255) R. **No preallocated snapshot ID, figures or provider fixture ID.** |
| Evaluation queued response | Proposed 202: operationId UUID, status PENDING, statusUrl. Member 3 can store safe returned metadata, but the upstream worker/polling endpoint and polling contract are not implemented. |
| EligibilityResult | eligibilityResultId/evaluationRequestId/applicationId/verificationId/financialSnapshotId UUIDs R; applicationRevision positive integer R; calculationVersion string R; evaluatedAt timestamp R; asOfDate date R; requestedAmount positive money R; currency INR R; outcome EVALUATED/ACTION_REQUIRED R; reasonCodes string[] R; lenderResults LenderResult[] R. EVALUATED additionally has averageMonthlyNetSales, averageMonthlyPayout and returnRatePercent two-decimal strings. Unknown summaries in ACTION_REQUIRED are omitted. |
| LenderResult | lenderId/productId UUIDs R; ruleVersion string R; indicativeMaxAmount money R; eligible boolean R; reasonCodes string[] R; routingPriority nonnegative integer R. |

Eligibility owns financial snapshots and uses the last three complete calendar months before captured asOfDate. Repeated dataset identity with unchanged content reuses a snapshot; changed content requires a new version. Missing coverage differs from complete zero data. Member 1's proposed six-month revenue/debt response lacks Member 2's complete import fields; their mapping is an explicit upstream gate.

ACTION_REQUIRED has empty lenderResults and FINANCIAL_DATA_INCOMPLETE, FINANCIAL_DATA_INCONSISTENT or FINANCIAL_DATA_INSUFFICIENT. EVALUATED has product results; Orchestration selects one eligible covering product by routingPriority then productId. Root/data applicationId must match, with current revision, expected evaluationRequestId/verificationId and captured date/amount/currency. The publisher and aggregate-version agreement remain proposed. Safe operationId/statusUrl metadata does not imply polling exists. See the upstream schemas and team compatibility register.

## Workflow and lender submission (Member 3)

| Object | Fields |
|---|---|
| Workflow | applicationId UUID R; status WorkflowStatus R; processingHealth Health R; workflowVersion nonnegative integer R; applicationRevision positive integer R; revisionPending boolean R; lenderId/productId/submissionId UUIDs O before routing; reasonCode string O; verificationId/evaluationRequestId/financialSnapshotId/eligibilityResultId/cancellationId/eligibilityOperationId UUIDs O; asOfDate date O; eligibilityStatusUrl string O; processingIssues array R. The opaque authorisedRetrievalReference remains private. |
| ProcessingIssue | issueId qualified stable key R; operationId UUID R for inspect/replay; kind JOB/TASK/CALLBACK R; operation string R; applicationRevision positive integer R; resolved boolean R; processingHealth Health R; failureCode string R; informationRequestId/offerId/acceptanceId/cancellationId UUIDs O. Only safe active issues are exposed; no tokens/payloads. |
| Canonical lender submission | submissionId/applicationId UUIDs R; applicationRevision positive integer R; eligibilityResultId/financialSnapshotId/verificationId/consentId UUIDs R; consentVersion positive integer R; lenderId/productId UUIDs R; trusted sellerId string(120) recommended for new records; legacy sellerSubject string O; marketplaceSellerId string O (otherwise sellerId supplies the lender seller reference); registeredBusinessName string R; requestedAmount positive money R; currency INR R; loanPurpose enum R; originActor R; trace metadata O. Legacy requests require sellerSubject plus marketplaceSellerId; no subject is invented from sellerId. |
| Submission projection | submissionId/applicationId UUIDs R; lenderId/productId UUIDs R; applicationRevision positive integer R; status SubmissionStatus R; lenderApplicationId string(120) O until receipt; version nonnegative integer R; submittedAt/receivedAt timestamps C; cancellationAcknowledgementStatus O. Full authorized detail additionally includes the evaluated business profile/references and workflow. |
| Integration dispatch | submissionId/applicationRevision/lenderId R; status QUEUED/SENDING/SUBMITTED/OUTCOME_UNKNOWN/FAILED/CANCELLED (the latter is an internal cancellation tombstone); lenderApplicationId/receivedAt O. This technical dispatch state is distinct from the documented business submission status. |
| Queued command | submissionId UUID R; operationId UUID R; status QUEUED R. A 202 means durably queued, not accepted by lender. Poll the business read models and inspect failed tasks via admin operations. |

WorkflowStatus: AWAITING_CONSENT, VERIFYING, ACTION_REQUIRED, CHECK_FAILED, ELIGIBILITY_CHECKING, NO_ELIGIBLE_LENDER, READY_FOR_LENDER, SUBMITTING, LENDER_REVIEW, INFORMATION_REQUIRED, MANUAL_REVIEW, OFFER_AVAILABLE, OFFER_ACCEPTED, OFFER_DECLINED, OFFER_EXPIRED, REJECTED, CANCELLED.

SubmissionStatus: CREATED, SENDING, SUBMITTED, INFORMATION_REQUIRED, MANUAL_REVIEW, APPROVED, REJECTED. Cancellation acknowledgement is tracked separately; APPROVED never means seller acceptance.

Health: OK, RETRY_PENDING, OUTCOME_UNKNOWN, MANUAL_INTERVENTION_REQUIRED. Technical errors never set REJECTED/CHECK_FAILED. One successful task clears only its own issue. Terminal business states have no outgoing transitions, but acknowledgement and technical recovery updates remain auditable.

## Questions, decisions, offers and callbacks

| Object | Fields |
|---|---|
| InformationRequest | informationRequestId/submissionId UUIDs R; questions array R (1..50); each question has nonblank questionId string and prompt string(1000); status OPEN/ANSWERED/CANCELLED R; version nonnegative integer R; createdAt timestamp R; answeredAt timestamp C; answers array O; syntheticDocumentReferences array O; lenderAcknowledgementStatus O. Superseded open questions use CANCELLED, not a new enum value. |
| InformationResponse | expectedInformationVersion nonnegative integer R; answers nonempty array R; each has questionId string R, answerText string(4000) R; syntheticDocumentReferences array O, default empty. References match `synthetic://[A-Za-z0-9_./-]{1,200}`; real uploads are out of scope. Exact question-ID set required; duplicate answers rejected. |
| LenderDecision | decisionId UUID R; decision APPROVED/REJECTED/MANUAL_REVIEW/INFORMATION_REQUIRED R; decidedAt UTC timestamp R in normalized result (server-generated once); reasonCode string C for rejection; reasonText string O; offer complete OfferTerms O on approval; informationRequestId/questions C for information-required decision. |
| OfferTerms | offerId UUID R; offeredAmount positive money R; currency INR R; annualInterestRatePercent percentage R; interestRateType FIXED R; tenureMonths integer 1..120 R; repaymentFrequency MONTHLY R; feesAmount nonnegative money R; termsReference string R; expiresAt UTC timestamp R; initial version 0 R. |
| Offer projection | OfferTerms plus submissionId UUID R; status ACTIVE/ACCEPTED/DECLINED/EXPIRED/WITHDRAWN R; version nonnegative integer R; lenderAcknowledgementStatus PENDING/ACKNOWLEDGED/FAILED R; acceptanceId/acceptedAt C after acceptance. |
| Offer action | expectedOfferVersion nonnegative integer R; Idempotency-Key R. Server creates stable acceptanceId/declineId; acceptedAt is frozen at local acceptance. Expiry is checked using current UTC under the same lock/version guard. |
| Workflow action | expectedWorkflowVersion nonnegative integer R; Idempotency-Key R. Cancellation/financial-refresh return `{operationId,workflow}` with 202. Refresh is limited to authorized financial-data ACTION_REQUIRED and one attempt per unchanged revision. |
| LenderCallback | eventId UUID R; lenderSequence positive integer R; applicationId/submissionId UUIDs R; applicationRevision positive integer R; lenderId UUID R; lenderApplicationId string R; eventType implemented callback topic R; occurredAt timestamp R; data object R; originActor/correlationId/causationId O. Reuse eventId and sequence on retries. |
| Processing failed/recovered | submissionId/applicationRevision/lenderId plus optional lenderApplicationId; taskId UUID (callback event ID for CALLBACK); operation enum; optional child IDs. Failed additionally includes failureCode, outcomeUnknown and processingHealth. Recovery does not erase unrelated failures. |
| Revision guard | operationId UUID R; expectedRevision/newRevision positive integers R; originActor Actor R. newRevision must equal expectedRevision+1; resource must allow correction before dispatch. |

An approval without complete terms stays in review. Valid approval/offer while questions remain open closes those questions and moves to OFFER_AVAILABLE. Local OFFER_ACCEPTED means acceptance is durably recorded; lenderAcknowledgementStatus describes eventual delivery separately. A late acknowledgement never opens a terminal application. Out-of-order callbacks remain pending until receipt/sequence prerequisites exist. A failed callback must be replayed with its original event ID after the cause is corrected.

## Two lender wire formats and isolated storage

Bank A uses `/mock/lenders/bank-a/applications`, submissionReference/applicationReference/sellerReference/registeredBusinessName/requestedFacility/currency/facilityPurpose/financialSnapshotReference/consentId/consentVersion and a receipt containing bankApplicationId, submissionReference, processingState, acceptedAt.

NBFC B uses `/mock/lenders/nbfc-b/cases`, caseReference/credflowApplicationId/merchantCode/legalName/requestedLimit/currencyCode/facilityCode/financialDataReference/consentReference/consentRevision and a receipt containing caseId, caseReference, statusCode, receivedTime. Shared revision/lender/product/eligibility/verification/provenance fields remain explicit.

Same reference and same payload returns the original receipt; same reference and different payload returns 409. A missing reference lookup returns 404. Timeouts require lookup/reconciliation before another creation attempt. Bank A and NBFC B persist into separate Oracle users/schemas (`CF_SIM_BANK_A`, `CF_SIM_NBFC_B`) through routed data sources. Orchestration and Integration use separate service users. Manual table setup remains the user's responsibility; no H2 or cross-service entity sharing is introduced.

## Errors, pagination and compatibility

Errors, including authentication failures, use `{code,message,correlationId,fieldErrors}`. 400 is malformed/invalid input; 401 missing/invalid authentication; 403 insufficient role/scope; 404 missing or deliberately hidden unauthorized resources; 409 stale version, conflicting replay or invalid state; 202 durable queued work; 200 a completed local read/action. Pagination is `{items,page,size,totalElements}`, page >=0, size 1..100. Offers, information requests and submission queues use it.

Idempotency covers the authenticated actor, operation/resource and request content for seller commands. Integration/simulator commands use a UUID key; repeated same key/payload returns the recorded result, changed content returns 409. Business versions, input revision and workflowVersion are different counters. SchemaVersion 1.0 changes must be agreed across teammates; additive optional fields require compatible consumers, breaking changes require a new contract version. No token, password, raw identity document or raw financial transaction belongs in general audit/log payloads. Audit owns its safeDetails projection and retention.

All incoming teammate contracts and Audit/Gateway behavior remain joint integration requirements until exercised against Members 1/2's real services. Contract fixture validation and unit tests are evidence about this repository, not proof of an end-to-end deployment.

The exact supplied Member 1/2 documents are in contracts/upstream/. See docs/team-contract-alignment.md for source-backed implementation gates, port/seed alignment and the application event/Auth/financial-shape incompatibility register. Integration now runs at 8087 because 8085 belongs to Auth. Existing Audit consumes only its template application.submitted; desired subscribers in topics.md do not prove those subscriptions are implemented.

Missing financial authority can be retried through Member3's existing financial-refresh action once per unchanged revision after authorization is available. FINANCIAL_RETRIEVAL_REFERENCE_REQUIRED starts a new durable Consent verification attempt; the seller never supplies a retrieval reference or financial figures. Normal consent, identity, version and no-dispatch guards remain in force.
