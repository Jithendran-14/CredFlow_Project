# eligibility-service

See the configured project's [root README](../../../README.md) and [verification guide](../../../tests/README.md) for startup, Oracle migrations, current service identities and executed scenarios. This service runs on 8083.

Eligibility uses its own Oracle financial imports, immutable snapshots, PL/SQL aggregation and versioned lender-product rules. Owning lender officers use GET /api/v1/lender-products and PUT /api/v1/lender-products/{id}/rules manage current versions; GET /api/v1/lender-products/{id}/rules/history exposes recorded history. New versions preserve past assessments. Deactivated latest versions never fall back to earlier active versions. See contracts/scope-completion.openapi.yaml at the project root.
