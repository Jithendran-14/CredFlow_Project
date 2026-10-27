# audit-service

See the configured project's [root README](../../../README.md) and [verification guide](../../../tests/README.md) for startup, Oracle migrations, current service identities and executed scenarios. This service runs on 8086.

Audit consumes canonical workflow events including application.submitted and application.revised. Correction audit stores revision/operation/amount/purpose metadata while excluding the business-profile payload. Event inbox identity prevents duplicate history. The existing user-requested UI hides application.status.changed entries without deleting those records from Oracle; the Timeline tab reads their recorded transitions separately.
