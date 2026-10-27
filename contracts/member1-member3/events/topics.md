# Versioned Kafka topics

Kafka key: applicationId. Every topic has a self-contained JSON Schema and positive fixture. Schemas constrain the whole envelope and payload; no external schema resolver or package installation is needed.

| Topic | Producer | Member 3 consumers / desired external consumers |
|---|---|---|
| application.submitted | application-service | orchestration-service, audit-service |
| application.revised | application-service | orchestration-service, audit-service |
| consent.updated | consent-verification-service | orchestration-service, audit-service |
| verification.completed | consent-verification-service | orchestration-service, audit-service |
| eligibility.completed | eligibility-service | orchestration-service, audit-service |
| lender.application.submitted | lender-integration-service | orchestration-service, audit-service |
| lender.information.requested | lender-integration-service | orchestration-service, audit-service |
| lender.information.answered | lender-integration-service | orchestration-service, audit-service |
| lender.decision.received | lender-integration-service | orchestration-service, audit-service |
| loan.offer.created | lender-integration-service | orchestration-service, audit-service |
| loan.offer.withdrawn | lender-integration-service | orchestration-service, audit-service |
| loan.offer.acknowledged | lender-integration-service | orchestration-service, audit-service |
| lender.cancellation.acknowledged | lender-integration-service | orchestration-service, audit-service |
| lender.processing.failed | lender-integration-service | orchestration-service, audit-service |
| lender.processing.recovered | lender-integration-service | orchestration-service, audit-service |
| lender.information.response.recorded | orchestration-service | audit-service |
| loan.offer.accepted | orchestration-service | lender-integration-service, audit-service |
| loan.offer.declined | orchestration-service | lender-integration-service, audit-service |
| loan.offer.expired | orchestration-service | audit-service |
| application.status.changed | orchestration-service | application-service, audit-service |
| consent.applied | orchestration-service | consent-verification-service, audit-service |

Keep eventId/payload stable on retries. Commit outbox and business changes together; consume into a durable inbox in the same service-local transaction. Aggregate versions belong to a producer/aggregate. Only workflowVersion orders the seller-facing status projection. A Kafka key does not order separate topics. Persist early events until prerequisites arrive and never reopen a terminal workflow.

Correlation IDs continue across jobs, lender calls, callbacks and outgoing events. causationId refers to the initiating event/command when available. actor is the authenticated immediate actor; originActor is verified provenance. Omit absent optional fields rather than serializing null UUIDs.

Current integrated boundaries are implemented: Member 1 publishes application.submitted and application.revised from a transactional outbox; Orchestration and Audit consume them. Corrections require a registered application-service identity with workflow.revision, an authorized revision guard and an atomic application update/outbox commit. previousRevision is an optional backwards-compatible application.revised field; current publishers include it. Financial permission is renewed for the new revision, and unchanged terminal outcomes are never reopened by delayed correction events. Member 2 evaluates authorized imported financial records with Oracle PL/SQL, persists snapshots and publishes the canonical eligibility.completed result. Consent is effective only after Orchestration applies it. The current API additions and examples are in contracts/scope-completion.openapi.yaml and contracts/application.revised.example.json. Historical upstream drafts remain references, not claims about current availability.

Recovery identifies a particular task/callback and cannot clear unrelated technical failures. Missing/inconsistent/insufficient financial data uses ACTION_REQUIRED and no lenderResults. All evaluable results use EVALUATED with per-product eligible flags; no automatic second-lender fallback.

## Dead-letter provisioning

Member 2 must also provision `<source-topic>.DLT` for every topic consumed by
Orchestration or Integration. Keep at least the source topic's partition count
because the Kafka error recoverer retains the original partition. Apply separate
producer/consumer ACLs; a producer name inside JSON is not authentication.

Malformed/unpersistable Kafka records reach the source topic's DLT after the
listener retries. A persisted inbox item that exhausts business processing stays
DEAD in its owner's database and sends its unchanged original event to the DLT.
The DLT transport topic differs from the envelope's original eventType; consumers
must preserve the original event ID, key, actor and payload. These are diagnostic
copies, not another business transition or a new event contract.

Inspect the cause before replay. Persisted DEAD inbox items use the owning
service's protected delivery replay API. Recovery of a record that never entered
the inbox is a controlled Kafka operations task with Member 2; do not publish
replacement business outcomes or grant browser clients Kafka access.
