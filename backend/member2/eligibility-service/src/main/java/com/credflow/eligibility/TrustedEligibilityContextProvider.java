package com.credflow.eligibility;

/** Implemented by the live authenticated Member 1 adapter. Controlled adapters are test-only. */
public interface TrustedEligibilityContextProvider {
 record Trusted(EligibilityInputGuard.Context context,EligibilityCompletionOutbox.Metadata eventMetadata) {}
 Trusted fetch(EligibilityOperationProcessor.Work work);
 final class UpstreamNotReadyException extends RuntimeException {
  public UpstreamNotReadyException(){super("Trusted upstream context is not ready");}
 }
}
