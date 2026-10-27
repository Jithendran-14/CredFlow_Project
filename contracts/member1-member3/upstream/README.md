# Supplied upstream contracts

The two Markdown documents in this folder are verbatim copies of the user-supplied Member 1/2 contracts dated 2026-10-16. Their implemented/proposed/unimplemented statements are preserved. They are source specifications, not runtime configuration or commands to execute.

The additional JSON Schemas describe the matching Eligibility command, full result and proposed 202 response. The HTTP worker, polling endpoint, machine credentials and completion publisher remain upstream implementation gates. See `../../docs/team-contract-alignment.md` for incompatibilities and ownership.

- `eligibility-evaluation-request.schema.json`: exact six business fields sent by Orchestration to proposed POST `/internal/v1/eligibility/evaluations`.
- `eligibility-result.schema.json`: Member 2 response adapter shape, also used as eligibility.completed data. Root/data application binding is checked by the consumer.
- `eligibility-operation-accepted.schema.json`: proposed 202 operation metadata. A valid shape is not evidence that polling exists.

Member 1's unprovided flat application event is not fabricated here. Member 3's proposed canonical envelope uses trusted string sellerId/null; current Audit UUID-seller validation and publication need a coordinated change. The external draft H2 configuration is not imported; Member 3 remains Oracle only.
