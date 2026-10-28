package com.credflow.eligibility.integration;

import com.credflow.eligibility.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class LiveEligibilityContextProvider implements TrustedEligibilityContextProvider {
	private final Member1Client client;
	private final ApplicationContractMapper applications;
	private final FinancialDataContractMapper financial;

	public LiveEligibilityContextProvider(Member1Client client, ApplicationContractMapper applications,
			FinancialDataContractMapper financial) {
		this.client = client;
		this.applications = applications;
		this.financial = financial;
	}

	public Trusted fetch(EligibilityOperationProcessor.Work work) {
		var c = work.command();
		String correlation = work.correlationId();
		var before = applications.application(client.application(c.applicationId(), correlation), c.applicationId(),
				c.applicationRevision(), c.asOfDate());
		var response = client.financial(c.applicationId(),
				Map.of("applicationRevision", c.applicationRevision(), "verificationId", c.verificationId(), "asOfDate",
						c.asOfDate(), "authorisedRetrievalReference", c.authorisedRetrievalReference()),
				c.evaluationRequestId(), correlation);
		// Recheck after remote retrieval: do not calculate using application inputs
		// changed during that call.
		var after = applications.application(client.application(c.applicationId(), correlation), c.applicationId(),
				c.applicationRevision(), c.asOfDate());
		if (!before.equals(after))
			throw new IllegalArgumentException("Application changed during financial retrieval");
		var mapped = financial.mapFinal(response, new FinancialDataContractMapper.Expected(c.applicationId(),
				c.applicationRevision(), c.verificationId(), after.sellerId(), c.asOfDate()));
		var context = new EligibilityInputGuard.Context(after.application(),
				new EligibilityInputGuard.Verification(c.verificationId(), c.applicationId(), c.applicationRevision(),
						"VERIFIED"),
				mapped.consent(), mapped.financialData());
		return new Trusted(context,
				new EligibilityCompletionOutbox.Metadata(correlation, c.applicationRevision(),
						new EligibilityCompletionOutbox.Actor("SYSTEM_CLIENT", "eligibility-service", null), null,
						c.evaluationRequestId()));
	}
}
