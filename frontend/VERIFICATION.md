# Frontend verification

Current verification: 21 October 2026, configured local installation at C:\innovation\CredFlow.

## Executed checks

- All 15 existing Node adapter/security/presentation tests passed against the current frontend and portal server.
- Chrome checks passed for admin rule editing and history, seller correction input with expected version/revision, pending-operation protection, role-specific navigation and one top Refresh button.
- Desktop and 390-pixel mobile checks completed without browser errors or page-level horizontal overflow. Timeline remains absent from application Overview.
- Live Oracle/Kafka journey passed: seller registration and initial sharing, marketplace submission, missing financial history, versioned correction, renewed financial permission, fresh eligibility, lender question/answer, real PDF upload/download, distinct requested/offered amounts, offer acceptance and lender acknowledgement.
- Backend and native Oracle checks confirmed correction before/after snapshots and rule activation/version behavior. Non-admin rule access and cross-seller correction/document access were denied.

The Chrome form checks used controlled API fixtures to inspect form payloads and rendering. The separate live API journey used the real running Auth, Oracle-backed services and Kafka. Neither the frontend nor its normal service responses substitute these fixtures for missing database records.

Reports and account records are private under backend/runtime/private/verification. The tests are opt-in because they create named verification records. Health checks alone do not demonstrate every workflow. Original isolated Oracle integration tests require their own explicit database settings; skipped tests are not reported as passes.

2026-10-22 permission update: rule management moved from Admin to the owning lender portal. Prior admin-rule checks above describe the earlier policy and are superseded. Current checks cover lender ownership and admin denial.

Verified 2026-10-22: six targeted JUnit tests and all sixteen frontend contract tests passed. Live direct Java API tests denied admin and cross-lender rule access; both lender lists were scoped. Both lender portals displayed only their products, editable forms and history; the Admin portal exposed no rule navigation or edit controls. Live rule updates and native Oracle activation checks passed, original business values restored, version history retained. No database migration.


2026-10-22 local password recovery verification:
- Auth: 39 JUnit tests passed, including 12 new recovery checks; frontend: all 17 contract tests passed.
- Live Oracle/Mailpit API regression passed using a fresh verification seller. Unknown/disabled usernames returned the generic receipt without mail; valid dummy addresses received messages. Oracle contained SHA-256 token hashes and BCrypt passwords. Replacement, expiry, password confirmation, unchanged password, reuse, exact-once concurrent consumption and rate limits were checked. Old password login failed; new password login succeeded.
- Real Chrome checks passed on all five portals. A separate verification seller completed forgot password -> captured email -> reset -> login. Mismatch and used-link messages, fragment removal, mobile width and absence of browser errors passed.
- Controlled stopped-SMTP and rejecting-SMTP checks passed. Safe 503 errors were returned; failed delivery rolled back new-token creation and old-token invalidation, leaving the prior link usable. Mailpit was restored afterward. Database outage response handling was checked in unit tests; the live Oracle server was not stopped.
- Mailpit v1.31.6 was downloaded from its official release and SHA-256 verified. Windows rejected the attempted memory-only database argument; startup uses the supported default temporary inbox storage. All CredFlow application and account persistence remains Oracle. Inbox messages are not restored across Mailpit restarts. Mailpit ports 1025/8025 are bound to loopback only, with no external relay configured.
- Migration added only the two recovery tables; no pre-existing user password was changed by verification. Three clearly named recovery verification sellers were registered; their final credentials are kept only under backend/runtime/private/verification. Existing stateless JWT access tokens expire normally rather than being revoked by a password reset.


2026-10-22 governing Phase 0 alignment verification:
- Kept marketplace/profile management, Mailpit recovery and optional lender terms PDFs. The loan workflow follows CredFlow - Phase 0 (3).pdf. All business/account data remains in Oracle; verification and lender providers remain simulations.
- Live real-Auth/Oracle/Kafka Phase 0 regression passed: adult prototype KYC, DRAFT intake, same-purpose duplicate reference, simultaneous different purposes, consent after decline, routing/acknowledgement through Bank A, NBFC B and Bank C, persisted credit band/disposable income/tenure repayment estimates, owning-marketplace information response, assigned-lender decisions and mandatory rejection reason, Bank A/Bank C acceptance without a PDF, missing-credit recovery, withdrawal/renewal on the same submission, insufficient-affordability NOT_ELIGIBLE without dispatch, and audit arrival.
- Existing live correction journey passed: missing-month action, owner-only/idempotent correction to revision 2, renewed financial permission, fresh evaluation, denial after dispatch, seller information response, optional PDF upload/download, amount separation, acceptance/replay, lender acknowledgement and correction/acceptance audit.
- Chrome used the actual running APIs for seller request submission and Oracle-backed reads. Age validation preserves entered values. Saved tenure and fictional KYC reference were checked through the application API. Desktop and 390px layouts passed without page errors or horizontal overflow. Marketplace credit-band entry, Bank C-only rules and Admin rule denial passed.
- The live Admin audit screen hides application.status.changed while showing loan.offer.accepted. Native Oracle checks independently confirmed status-change audit rows are retained, and corrected application KYC/tenure/revision plus calculated credit/disposable-income fields are stored.
- All 19 frontend contract/security tests passed. Auth Phase0 validation and the targeted application, eligibility/rules, consent, audit and workflow suites passed. The full Member 3 suite passed; after the missing-credit fix its 69 relevant workflow/document/contract/timeline checks passed again. Consent adds 3 safe-error tests (6 focused consent tests total). Original database-dependent JUnit suites require their documented isolated-schema settings and are not counted as passed merely because they exist.
- Live lender-rule write regression passed, including native Oracle persistence, owner-only access, append-only versions, stale edits/retry identity, deactivate/reactivate and restoration of the original business values through new versions.
- Controlled consent domain failures now retain their HTTP status/code. Invalid fields/body receive clear 400 responses; ownership denies access; storage failures return a safe 503 without Oracle/SQL details. Valid consent decline/renewal and withdrawal/renewal were tested live.
- Reports and test account credentials are private under backend/runtime/private/verification. Clearly named synthetic verification records were added; existing seller records and audit history were preserved. Source/database backups remain in the Codex workspace under work/phase0-alignment.

2026-10-22 user-requested verification-data cleanup:
Removed the 27 explicitly labelled verification sellers, their 45 test applications, one verification marketplace/operator and its machine identity, and related consent, financial, workflow, lender, offer and audit test records. The eleven-schema backup and exact-row cleanup plan are retained in the Codex workspace at work/test-data-cleanup-20261022. Before/after comparison confirmed all 1,055 retained rows and all database constraints were unchanged; the original 8 sellers and 9 applications remain. Earlier verification reports describe historical runs whose synthetic records have now been removed. No automatic test seeding was enabled.

2026-10-22 eligibility-stall repair:
Aarav Sharma's saved application had exhausted five eligibility attempts after Consent rejected reuse of a legacy dataset reference with FINANCIAL_DATASET_VERSION_CONFLICT. New provider requests now use a separate credit-band schema namespace and dataset version 2; historical request receipts and snapshots remain immutable. Orchestration polls its authenticated operation receipts and moves matching failed assessments to ACTION_REQUIRED with ELIGIBILITY_PROCESSING_FAILED and a financial-refresh retry. Responses for another application/operation/revision and late receipts are ignored. Pending/successful operations and temporary status-read outages do not create a rejection. Seller guidance explains both technical retry and missing credit information.
74 targeted workflow/contract/timeline/scheduler tests, 2 provider tests (including legacy receipt compatibility), and all 19 frontend tests passed. The actual saved application was recovered through the normal seller refresh API: its new eligibility operation SUCCEEDED, a version-2 dataset and result were saved in Oracle, and its current outcome is ACTION_REQUIRED / CREDIT_INFORMATION_REQUIRED because the existing profile has NOT_AVAILABLE credit information. The old failed attempt remains recorded. No verification sellers or test loans were added. The marketplace must record the missing credit band and review monthly history before the seller refreshes the assessment again.


## Phase 0 completion verification - 22 October 2026

Completed the four exception/audit gaps identified in the PDF review: permitted lender rejection codes in workflow responses; manual-review decisions with exact lender questions; financial-consent expiry/withdrawal gates with same-submission renewal; and durable actor-attributed financial-consent lifecycle history. Status-change entries remain hidden in the UI.

Verification: 254 Member 3 checks, 8 focused Consent checks, 104 Audit checks and 19 frontend checks passed (385 checks; no failures). The final orchestration changes were rechecked afterward. Oracle rollback-only diagnostics exercised real consent outbox inserts and real workflow mappings, expiry pause, renewal, manual question creation and rejection reasons; no diagnostic records were retained. Live login, ownership restrictions, machine-only permission reads and browser rendering passed. All 11 services and 6 portals are healthy. Kafka delivered all 19 recovered financial-consent lifecycle events into Audit.

Preservation: 58 business/account tables retained their original rows and values; no sellers, applications or financial datasets were seeded. Expected changes are the nullable workflow checkpoint column, legitimate consent-expiry authorisation updates, lifecycle event counters and audit/delivery records. The fresh write-based loan journey was not rerun against the user's records; the changed state transitions were verified with unit tests and rollback-only Oracle diagnostics instead.


## 26 October 2026: shared lender portal and onboarding

- All existing Bank A, NBFC B and Bank C accounts signed in at port 5176. Live API/browser checks verified lender-owned rule lists, cross-lender history denial, admin-only lender/routing routes, the creation form and mandatory priority reason.
- Oracle rollback-only checks verified atomic lender/account/inactive-product creation, BCrypt password matching, actual signed JWT login and ownership claims, wrong-password rejection and idempotent replay. No test lender persisted.
- Oracle rollback-only checks verified a priority reorder changes all affected rule versions with reasons, preserves eligibility values, remains retry-safe and keeps routing hidden from lender responses.
- A dynamic registered bank was tested for submission, lookup, offer, acceptance and lender-specific callback records within rollback-only Oracle transactions. No simulator test case or callback persisted.
- Health checks passed for all 11 backend services, Kafka, Mailpit and four portals. No new broker-backed loan journey was created during this change.
