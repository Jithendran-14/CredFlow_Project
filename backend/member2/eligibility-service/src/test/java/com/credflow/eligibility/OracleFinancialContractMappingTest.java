package com.credflow.eligibility;

import com.credflow.eligibility.integration.FinancialDataContractMapper;
import com.credflow.eligibility.integration.ApplicationContractMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "ORACLE_FINANCIAL_TESTS", matches = "true")
class OracleFinancialContractMappingTest {
	@BeforeAll
	static void setup() {
		OracleEligibilityCompletionTest.setup();
	}

	EligibilityTrustedPipeline.Completed evaluate(EligibilityInputGuard.Request request, ObjectNode payload) {
		var mapped = Member1FinancialFixture.MAPPER.map(payload.toString(), Member1FinancialFixture.expected(payload));
		var base = EligibilityTestContext.context(request);
		ObjectNode application;
		try (var stream = getClass().getResourceAsStream("/contracts/member1/application-details-v1.json")) {
			application = (ObjectNode) Member1FinancialFixture.JSON.readTree(stream);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		application.put("applicationId", request.applicationId().toString());
		application.put("applicationRevision", request.applicationRevision());
		var app = new ApplicationContractMapper(Member1FinancialFixture.JSON).application(application.toString(),
				request.applicationId(), request.applicationRevision(), request.asOfDate());
		// VERIFIED evidence is supplied separately by the trusted test context, never
		// invented from the financial DTO.
		var context = new EligibilityInputGuard.Context(app.application(), base.verification(), mapped.consent(),
				mapped.financialData());
		return OracleEligibilityCompletionTest.pipeline.evaluate(request, context, EligibilityTestContext.metadata());
	}

	@Test
	void suppliedPartialResponseKeepsZeroAndNullInOracleAndNeedsAction() {
		var request = EligibilityTestContext.request();
		var payload = Member1FinancialFixture.forRequest(request);
		OracleFinancialSnapshotTest.tx.executeWithoutResult(s -> {
			var done = evaluate(request, payload);
			var jdbc = OracleEligibilityCompletionTest.jdbc;
			assertThat(done.result().result().reasonCodes()).containsExactly("FINANCIAL_DATA_INCOMPLETE");
			assertThat(done.result().result().averageMonthlyNetSales()).isNull();
			var provenance = jdbc.queryForMap(
					"SELECT b.source_reference,b.source_data_version FROM eligibility_evaluation e JOIN financial_snapshot f ON e.financial_snapshot_id=f.snapshot_id JOIN financial_import_batch b ON f.import_batch_id=b.import_batch_id WHERE e.eligibility_result_id=?",
					done.result().eligibilityResultId().toString());
			assertThat(provenance.get("SOURCE_REFERENCE")).isEqualTo("FIN-DATA-0001");
			assertThat(((Number) provenance.get("SOURCE_DATA_VERSION")).intValue()).isEqualTo(1);
			assertThat(jdbc.queryForObject(
					"SELECT COUNT(*) FROM financial_source_month WHERE import_batch_id IN (SELECT import_batch_id FROM financial_import_batch WHERE application_id=?) AND gross_sales=0 AND order_count=0",
					Integer.class, request.applicationId().toString())).isEqualTo(1);
			assertThat(jdbc.queryForObject(
					"SELECT COUNT(*) FROM financial_source_month WHERE import_batch_id IN (SELECT import_batch_id FROM financial_import_batch WHERE application_id=?) AND gross_sales IS NULL AND returned_order_count IS NULL",
					Integer.class, request.applicationId().toString())).isEqualTo(1);
			s.setRollbackOnly();
		});
	}

	@Test
	void completeResponseReachesCalculationPersistenceAndOutbox() {
		var request = EligibilityTestContext.request();
		var payload = Member1FinancialFixture.full(Member1FinancialFixture.forRequest(request));
		OracleFinancialSnapshotTest.tx.executeWithoutResult(s -> {
			var done = evaluate(request, payload);
			assertThat(done.result().result().averageMonthlyNetSales()).isEqualByComparingTo("550000.00");
			assertThat(done.result().result().lenderResults()).hasSize(2)
					.allMatch(EligibilityCalculator.LenderResult::eligible);
			assertThat(OracleEligibilityCompletionTest.jdbc.queryForObject(
					"SELECT COUNT(*) FROM event_outbox WHERE event_id=?", Integer.class, done.eventId().toString()))
					.isEqualTo(1);
			s.setRollbackOnly();
		});
	}

	@Test
	void aFinancialVerificationIdDoesNotBypassFailedIdentityEvidence() {
		var request = EligibilityTestContext.request();
		var payload = Member1FinancialFixture.forRequest(request);
		var mapped = Member1FinancialFixture.MAPPER.map(payload.toString(), Member1FinancialFixture.expected(payload));
		var base = EligibilityTestContext.context(request);
		var context = new EligibilityInputGuard.Context(base.application(),
				new EligibilityInputGuard.Verification(request.verificationId(), request.applicationId(), 1, "FAILED"),
				mapped.consent(), mapped.financialData());
		assertThatThrownBy(
				() -> OracleFinancialSnapshotTest.tx.executeWithoutResult(s -> OracleEligibilityCompletionTest.pipeline
						.evaluate(request, context, EligibilityTestContext.metadata())))
				.hasMessage("VERIFICATION_NOT_CURRENT_OR_VERIFIED");
		assertThat(OracleEligibilityCompletionTest.jdbc.queryForObject(
				"SELECT COUNT(*) FROM financial_import_batch WHERE application_id=?", Integer.class,
				request.applicationId().toString())).isZero();
	}

	@Test
	void retriedResponseReusesTheStoredSnapshotResultAndEvent() {
		var request = EligibilityTestContext.request();
		var payload = Member1FinancialFixture.full(Member1FinancialFixture.forRequest(request));
		OracleFinancialSnapshotTest.tx.executeWithoutResult(s -> {
			var first = evaluate(request, payload);
			((ObjectNode) payload.path("source")).put("retrievedAt", "2026-10-15T09:06:00Z");
			var second = evaluate(request, payload);
			assertThat(second.eventId()).isEqualTo(first.eventId());
			assertThat(second.result().financialSnapshotId()).isEqualTo(first.result().financialSnapshotId());
			s.setRollbackOnly();
		});
	}
}
