package com.credflow.simulator.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/** Deterministic fictional provider logic; deliberately independent of HTTP DTOs. */
@Service
public class VerificationSimulator {
    public enum Status { VERIFIED, FAILED, PENDING, ACTION_REQUIRED }
    public record Request(UUID verificationRequestId, UUID applicationId, int applicationRevision,
                          String fixtureIdentityId) {}
    public record Result(UUID verificationRequestId, UUID applicationId, int applicationRevision,
                         String fixtureIdentityId, String providerReference, Status status,
                         String reasonCode, String message, Instant checkedAt) {}
    public record Scenario(String fixtureIdentityId, Status status, String reasonCode, String message) {}
    public record FixtureDocument(String description, List<Scenario> fixtures) {}
    private record RecordedCheck(Request request, Result result) {}
    public static class UnknownFixtureException extends IllegalArgumentException {
        public UnknownFixtureException() { super("Unknown fictional verification identity"); }
    }
    public static class RequestConflictException extends IllegalArgumentException {
        public RequestConflictException() { super("Verification request ID was reused with different inputs"); }
    }
    private com.credflow.simulator.history.MarketplaceHistorySource history;
    private final Map<String,Scenario> scenarios;
    private final Clock clock;
    private final ConcurrentMap<UUID,RecordedCheck> checks=new ConcurrentHashMap<>();

    @Autowired
    public VerificationSimulator(ObjectMapper json,com.credflow.simulator.history.MarketplaceHistorySource history) {
        this(json,new ClassPathResource("fixtures/verification-scenarios.json"),Clock.systemUTC());
        this.history=history;
    }
    public VerificationSimulator(ObjectMapper json, Resource fixtureResource, Clock clock) {
        this.clock=Objects.requireNonNull(clock);
        try(var input=fixtureResource.getInputStream()) {
            var document=json.readValue(input,FixtureDocument.class);
            if(document.fixtures()==null || document.fixtures().isEmpty())
                throw new IllegalArgumentException("Verification fixtures must not be empty");
            Map<String,Scenario> loaded=new HashMap<>();
            for(var s:document.fixtures()) {
                if(s==null || s.fixtureIdentityId()==null || !s.fixtureIdentityId().matches("DEMO-[A-Z0-9-]{1,80}")
                    || s.status()==null || s.message()==null || s.message().isBlank()
                    || (s.status()!=Status.VERIFIED && (s.reasonCode()==null || s.reasonCode().isBlank())))
                    throw new IllegalArgumentException("Invalid fictional verification fixture");
                if(loaded.putIfAbsent(s.fixtureIdentityId(),s)!=null)
                    throw new IllegalArgumentException("Duplicate fictional verification identity");
            }
            this.scenarios=Map.copyOf(loaded);
        } catch(IOException ex) { throw new IllegalStateException("Cannot read verification fixtures",ex); }
    }
    public Result verify(Request request) {
        Objects.requireNonNull(request,"Verification request is required");
        Objects.requireNonNull(request.verificationRequestId(),"Verification request ID is required");
        Objects.requireNonNull(request.applicationId(),"Application ID is required");
        if(request.applicationRevision()<1 || request.fixtureIdentityId()==null || request.fixtureIdentityId().isBlank())
            throw new IllegalArgumentException("Positive application revision and fixture identity are required");
        // Atomically record each request: retries reuse the original result and timestamp.
        var recorded=checks.compute(request.verificationRequestId(),(id,existing)->{
            if(existing!=null) {
                if(!existing.request().equals(request)) throw new RequestConflictException();
                return existing;
            }
            var scenario=scenarios.get(request.fixtureIdentityId());
            if(scenario==null && request.fixtureIdentityId().startsWith("DEMO-MARKETPLACE-") && history!=null){
                var reviewed=history.businessReviewed(request.fixtureIdentityId());
                if(reviewed.isPresent())scenario=new Scenario(request.fixtureIdentityId(),reviewed.get()?Status.VERIFIED:Status.ACTION_REQUIRED,reviewed.get()?null:"BUSINESS_DETAILS_REVIEW_REQUIRED",reviewed.get()?"Marketplace business details reviewed for the local verification simulator.":"Your marketplace must review the registered business details before verification can continue.");
            }
            if(scenario==null) throw new UnknownFixtureException();
            var result=new Result(id,request.applicationId(),request.applicationRevision(),request.fixtureIdentityId(),
                "mock-verification-"+id,scenario.status(),scenario.reasonCode(),scenario.message(),clock.instant());
            return new RecordedCheck(request,result);
        });
        return recorded.result();
    }
}
