# Verification

Run `check.cmd` first. Health confirms availability, not a complete workflow. Read the project README for the four portal ports, Oracle migrations and service identities.

All API write checks require `CREDFLOW_ALLOW_WRITE_TEST=true`. Run them from the configured project root or set `CREDFLOW_ROOT` explicitly. Private runtime account aliases supply the actual current usernames; secrets are never printed. The test scripts are not frontend fallback data.

- `node tests/lender-rules.cjs`: authorized rule reads, rejected admin/seller access and cross-lender reads and writes, versioned updates, idempotent retry, stale edits, deactivate/reactivate and preserved version history. Original business values are restored with new versions.
- `node tests/journey.cjs`: registration, marketplace history, seller request, automatic submission, both permissions, missing-month ACTION_REQUIRED, guarded application correction, revision 2 verification/eligibility, lender questions/answers, actual PDF attachment and byte-identical download, distinct requested/offered amounts, acceptance replay, lender acknowledgement and correction/acceptance audit. Creates one accepted verification loan.
- `node tests/missing-month.cjs`: distinct missing financial month, no lender dispatch and cancellation. Creates one cancelled verification loan.
- `node tests/marketplace-history.cjs`: marketplace isolation, editable source history, stale/replay protection, manual-submission denial, next-loan history version and cancelled applications.

Results are under `backend/runtime/private/verification`. Keep them private because verification account credentials are included in separate account files. Tests create named records rather than clearing tables or overwriting real seller data. A failed script prints its application reference for inspection; inspect it before rerunning.

JUnit sources live with each service. Use the installed Maven/Java toolchain or `build.cmd` to build the project. Oracle integration tests require their documented explicit environment flags and database configuration; they must run against Oracle, never H2 as a substitute. Some original Member 1 integration tests expect an isolated `CF_APPLICATION_TEST` schema. Do not point those at a live application schema to make them pass. Ordinary unit/security tests and the live API regression complement those separate schema tests.

Current feature-specific JUnit checks cover correction input, owner isolation/stale revision, rule input/lender ownership authorization, audit projection, workflow guards and existing security behavior. The complete live regression is required after any API or workflow contract change.

`node tests/password-recovery.cjs` exercises Mailpit password recovery using a newly registered verification seller: generic responses, token replacement, password confirmation, invalid/expired/used links, concurrent consumption, rate limits and old/new password login. Requires CREDFLOW_ALLOW_WRITE_TEST=true; existing user passwords are never changed. Controlled outage and browser checks are described in frontend/VERIFICATION.md.

`node tests/phase0-flow.cjs` verifies the governing Phase 0 workflow: adult KYC, DRAFT intake, purpose-scoped duplicates and simultaneous different purposes, consent renewal, live Bank A/NBFC B/Bank C adapter routing, stored credit/affordability assessment, marketplace response ownership, reasoned rejection, offer acceptance without mandatory PDF upload, missing-credit recovery and insufficient-affordability NOT_ELIGIBLE. Requires CREDFLOW_ALLOW_WRITE_TEST=true and the full project running. It creates clearly named synthetic verification sellers and preserves prior records.

Admin lender onboarding uses the shared lender portal at 5176. New inactive products are appended to routing order; admin priority updates shift intervening positions and require a reason. Focused regression tests include LenderManagementTest, RoutingPriorityServiceTest, RegisteredLenderConsentTest, RegisteredLenderIntegrationTest and RegisteredLenderSimulatorTest.
