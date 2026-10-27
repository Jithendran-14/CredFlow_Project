
package com.credflow.application.service;

import java.math.BigDecimal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.math.RoundingMode;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.credflow.application.dto.ApplicationRequest;
import com.credflow.application.dto.ApplicationRequest.BusinessProfile;
import com.credflow.application.dto.ApplicationResponse;
import com.credflow.application.entity.ApplicationEntity;
import com.credflow.application.entity.IdempotencyKeyEntity;
import com.credflow.application.entity.OutboxEventEntity;
import com.credflow.application.entity.SellerMappingEntity;
import com.credflow.application.exception.ApplicationNotFoundException;
import com.credflow.application.exception.IdempotencyConflictException;
import com.credflow.application.repository.ApplicationRepository;
import com.credflow.application.repository.IdempotencyKeyRepository;
import com.credflow.application.repository.OutboxEventRepository;
import com.credflow.application.repository.SellerMappingRepository;
import com.credflow.application.util.RequestFingerprint;
import com.credflow.application.dto.ApplicationContextResponse;
import com.credflow.application.dto.OwnershipCheckResponse;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.math.RoundingMode;
import java.time.ZoneOffset;

import com.credflow.application.dto.ApplicationSubmittedEvent;

import com.credflow.application.dto.ApplicationSubmittedEvent;

import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class ApplicationService {

    public static final java.util.Set<String> CLOSED_STATUSES = java.util.Set.of("CHECK_FAILED","NOT_ELIGIBLE","OFFER_ACCEPTED","OFFER_DECLINED","OFFER_EXPIRED","REJECTED","CANCELLED");
    public Map<String,Object> activeApplication(Jwt jwt) {return activeApplication(jwt,null);}
    public Map<String,Object> activeApplication(Jwt jwt,String purpose) {
        if(purpose!=null&&!java.util.Set.of("WORKING_CAPITAL","PURCHASE_INVENTORY","BUSINESS_EXPANSION").contains(purpose))throw new IllegalArgumentException("Invalid lending purpose.");
        if(jwt.getClaimAsStringList("roles")==null || !jwt.getClaimAsStringList("roles").contains("SELLER") || jwt.getClaimAsString("sellerId")==null) throw new AccessDeniedException("Seller identity is required");
        return (purpose==null?applicationRepository.findFirstBySellerIdAndStatusNotInOrderByCreatedAtDesc(jwt.getClaimAsString("sellerId"),CLOSED_STATUSES):applicationRepository.findFirstBySellerIdAndLoanPurposeAndStatusNotInOrderByCreatedAtDesc(jwt.getClaimAsString("sellerId"),purpose,CLOSED_STATUSES))
            .<Map<String,Object>>map(a -> Map.of("active",true,"applicationId",a.getPublicApplicationId(),"status",a.getStatus()))
            .orElseGet(() -> Map.of("active",false));
    }
    private static final String INITIAL_STATUS = "DRAFT";
    private static final String DEFAULT_CURRENCY = "INR";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private static final String APPLICATION_AGGREGATE = "APPLICATION";
    private static final String APPLICATION_SUBMITTED_EVENT =
            "application.submitted";
    private static final int EVENT_VERSION = 3;
    private static final String EVENT_SCHEMA_VERSION = "1.0";
    private static final String EVENT_PRODUCER = "application-service";

    private final ApplicationRepository applicationRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final SellerMappingRepository sellerMappingRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final RequestFingerprint requestFingerprint;
    private final ObjectMapper objectMapper;

    public ApplicationService(
            ApplicationRepository applicationRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            SellerMappingRepository sellerMappingRepository,
            OutboxEventRepository outboxEventRepository,
            RequestFingerprint requestFingerprint,
            ObjectMapper objectMapper) {

        this.applicationRepository = applicationRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.sellerMappingRepository = sellerMappingRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.requestFingerprint = requestFingerprint;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> list(int page, int size, Jwt jwt) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        var pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by("createdAt").descending());
        var roles = jwt.getClaimAsStringList("roles");
        org.springframework.data.domain.Page<ApplicationEntity> result;
        if (roles != null && roles.contains("ADMIN")) result = applicationRepository.findAll(pageable);
        else if (roles != null && roles.contains("SELLER") && jwt.getClaimAsString("sellerId") != null)
            result = applicationRepository.findBySellerId(jwt.getClaimAsString("sellerId"), pageable);
        else if (roles != null && roles.contains("MARKETPLACE") && jwt.getClaimAsString("marketplaceId") != null)
            result = applicationRepository.findByMarketplaceId(jwt.getClaimAsString("marketplaceId"), pageable);
        else throw new AccessDeniedException("Authorised application identity is required");
        return Map.of("items", result.getContent().stream().map(this::toResponse).toList(), "page", page, "size", size, "totalElements", result.getTotalElements());
    }

    // --------------------------------------------------
    // CREATE APPLICATION
    // --------------------------------------------------

    @Transactional
    public ApplicationResponse createApplication(
            ApplicationRequest request,
            String idempotencyKey,
            Jwt jwt) {

        // 1. Validate the incoming request.
        validateIdempotencyKey(idempotencyKey);
        validateBusinessRules(request);

        if (jwt == null) {
            throw new AccessDeniedException(
                    "Authenticated marketplace identity is required");
        }

        // 2. Get marketplace identity from the verified JWT.
        String authenticatedMarketplaceId =
                jwt.getClaimAsString("marketplaceId");

        if (authenticatedMarketplaceId == null
                || authenticatedMarketplaceId.isBlank()) {
            throw new AccessDeniedException(
                    "Marketplace identity is missing from JWT");
        }

        if (!("marketplace-submission-"+authenticatedMarketplaceId).equals(jwt.getClaimAsString("client_id")) ||
                 !java.util.Arrays.asList(String.valueOf(jwt.getClaimAsString("scope")).split(" ")).contains("application.submit"))
            throw new AccessDeniedException("Marketplace submission client grant is required");

        // 3. Prevent marketplace impersonation.
        String requestedMarketplaceId =
                request.marketplaceId().toString();

        if (!authenticatedMarketplaceId.equals(requestedMarketplaceId)) {
            throw new AccessDeniedException(
                    "Marketplace ID does not match authenticated user");
        }

        // Use the authenticated identity for database operations.
        String marketplaceId = authenticatedMarketplaceId;

        String fingerprint = requestFingerprint.calculate(request);

        // 6. Resolve seller identity from the trusted mapping table.
        // Never trust marketplaceSellerId directly as a CredFlow seller ID.
        String trustedSellerId =
                sellerMappingRepository
                        .findByMarketplaceIdAndMarketplaceSellerId(
                                marketplaceId,
                                request.marketplaceSellerId())
                        .map(SellerMappingEntity::getSellerId)
                        .orElseThrow(() -> new IllegalArgumentException("Choose a registered seller in your marketplace."));

        // Serialize every creation path on the stable trusted seller mapping before any replay checks.
        sellerMappingRepository.lockSeller(trustedSellerId);

        // 4. Check idempotency.
        var existingKey =
                idempotencyKeyRepository
                        .findByMarketplaceIdAndIdempotencyKey(
                                marketplaceId,
                                idempotencyKey);

        if (existingKey.isPresent()) {

            IdempotencyKeyEntity savedKey = existingKey.get();

            if (!savedKey.getRequestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(
                        "Idempotency-Key was already used with a different request");
            }

            // Same key and same payload: return the original application.
            // Do not create another application or outbox event.
            ApplicationEntity originalApplication =
                    applicationRepository
                            .findByPublicApplicationId(
                                    savedKey.getPublicApplicationId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Idempotency record references a missing application"));

            return toResponse(originalApplication);
        }

        // 5. Prevent duplicate marketplace requests.
        boolean duplicateRequest =
                applicationRepository
                        .findByMarketplaceIdAndMarketplaceRequestId(
                                marketplaceId,
                                request.marketplaceRequestId())
                        .isPresent();

        if (duplicateRequest) {
            throw new IllegalArgumentException(
                    "An application already exists for this marketplace request");
        }

        var existingPurpose=applicationRepository.findFirstBySellerIdAndLoanPurposeAndStatusNotInOrderByCreatedAtDesc(trustedSellerId,request.loanPurpose().name(),CLOSED_STATUSES);
        if(existingPurpose.isPresent())throw new com.credflow.application.exception.ActiveApplicationException(existingPurpose.get().getPublicApplicationId());

        // 7. Create application entity.
        ApplicationEntity entity = new ApplicationEntity();

        entity.assignPublicApplicationId(UUID.randomUUID().toString());
        entity.setApplicationReference(generateApplicationReference());

        entity.setMarketplaceId(marketplaceId);
        entity.setMarketplaceSellerId(request.marketplaceSellerId());
        entity.setSellerId(trustedSellerId);

        entity.setMarketplaceRequestId(request.marketplaceRequestId());
        entity.setSellerRequestReference(request.sellerRequestReference());

        entity.setRequestedAmount(
                new BigDecimal(request.requestedAmount()));

        entity.setCurrency(request.currency());
        entity.setLoanPurpose(request.loanPurpose().name());

        BusinessProfile profile = request.businessProfile();

        entity.setDisplayName(profile.displayName());
        entity.setBusinessName(profile.businessName());
        entity.setBusinessStartDate(profile.businessStartDate());
        if(profile.phase0()==null)throw new IllegalArgumentException("Preferred tenure, declared finances and prototype KYC are required.");profile.phase0().validate();
        entity.setPhase0Json(objectMapper.writeValueAsString(profile.phase0()));

        entity.setStatus(INITIAL_STATUS);
        entity.setApplicationRevision(1);
        entity.setWorkflowVersion(0);

        // 8. Save application in Oracle.
        ApplicationEntity savedApplication =
                applicationRepository.save(entity);

        // 9. Save idempotency record in the same transaction.
        IdempotencyKeyEntity keyEntity = new IdempotencyKeyEntity();

        keyEntity.setMarketplaceId(marketplaceId);
        keyEntity.setIdempotencyKey(idempotencyKey);
        keyEntity.setRequestFingerprint(fingerprint);
        keyEntity.setPublicApplicationId(
                savedApplication.getPublicApplicationId());

        idempotencyKeyRepository.saveAndFlush(keyEntity);

        // 10. Save application.submitted event in the same transaction.
        // This is only an Oracle insert; Kafka publishing happens later.
        saveApplicationSubmittedEvent(savedApplication, jwt);

        return toResponse(savedApplication);
    }

    // --------------------------------------------------
    // GET APPLICATION
    // --------------------------------------------------

    public ApplicationResponse getApplication(
            String publicApplicationId,
            Jwt jwt) {

        if (publicApplicationId == null
                || publicApplicationId.isBlank()) {
            throw new IllegalArgumentException(
                    "Application ID cannot be blank");
        }

        if (jwt == null) {
            throw new AccessDeniedException(
                    "Authentication is required");
        }

        UUID parsedId;

        try {
            parsedId = UUID.fromString(publicApplicationId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Application ID must be a valid UUID");
        }

        ApplicationEntity entity =
                applicationRepository
                        .findByPublicApplicationId(parsedId.toString())
                        .orElseThrow(() -> new ApplicationNotFoundException(
                                "Application not found"));

        List<String> roles = jwt.getClaimAsStringList("roles");

        if (roles == null) {
            throw new AccessDeniedException(
                    "JWT does not contain user roles");
        }

        // ADMIN can view all applications.
        if (roles.contains("ADMIN")) {
            return toResponse(entity);
        }

        // MARKETPLACE can view only its own marketplace's applications.
        if (roles.contains("MARKETPLACE")) {

            String authenticatedMarketplaceId =
                    jwt.getClaimAsString("marketplaceId");

            if (authenticatedMarketplaceId != null
                    && !authenticatedMarketplaceId.isBlank()
                    && authenticatedMarketplaceId.equals(
                            entity.getMarketplaceId())) {

                return toResponse(entity);
            }
        }

        // SELLER can view only applications linked to their trusted ID.
        if (roles.contains("SELLER")) {

            String authenticatedSellerId =
                    jwt.getClaimAsString("sellerId");

            String applicationSellerId = entity.getSellerId();

            if (authenticatedSellerId != null
                    && !authenticatedSellerId.isBlank()
                    && applicationSellerId != null
                    && !applicationSellerId.isBlank()
                    && authenticatedSellerId.equals(applicationSellerId)) {

                return toResponse(entity);
            }
        }

        throw new AccessDeniedException(
                "You are not allowed to view this application");
    }

    // --------------------------------------------------
    // TRANSACTIONAL OUTBOX
    // --------------------------------------------------
    private void saveApplicationSubmittedEvent(
            ApplicationEntity application,
            Jwt jwt) {

        String submittingSubject = jwt.getSubject();

        if (submittingSubject == null || submittingSubject.isBlank()) {
            throw new AccessDeniedException(
                    "Authenticated marketplace subject is required");
        }

        String eventId = UUID.randomUUID().toString();

        String correlationId = UUID.randomUUID().toString();

        String occurredAt =
                OffsetDateTime.now(ZoneOffset.UTC).toString();

        String requestedAmount =
                application.getRequestedAmount()
                        .setScale(2, RoundingMode.UNNECESSARY)
                        .toPlainString();

        // This is the first Application-owned event for a new application.
        // Future revision events will need durable stream sequencing.
        long aggregateVersion = 1L;

        ApplicationSubmittedEvent.Actor actor =
                new ApplicationSubmittedEvent.Actor(
                        "SYSTEM_CLIENT",
                        EVENT_PRODUCER,
                        application.getMarketplaceId()
                );

        ApplicationSubmittedEvent.Actor originActor =
                new ApplicationSubmittedEvent.Actor(
                        jwt.getClaimAsString("client_id") == null ? "HUMAN" : "SYSTEM_CLIENT",
                        jwt.getClaimAsString("client_id") == null ? submittingSubject : jwt.getClaimAsString("client_id"),
                        application.getMarketplaceId()
                );

        ApplicationSubmittedEvent.BusinessProfile businessProfile =
                new ApplicationSubmittedEvent.BusinessProfile(
                        application.getDisplayName(),
                        application.getBusinessName(),
                        application.getBusinessStartDate(),
                        phase0(application)
                );

        ApplicationSubmittedEvent.Data data =
                new ApplicationSubmittedEvent.Data(
                        application.getSellerId(),
                        application.getMarketplaceId(),
                        application.getMarketplaceSellerId(),
                        requestedAmount,
                        application.getCurrency(),
                        application.getLoanPurpose(),
                        application.getStatus(),
                        application.getApplicationRevision(),
                        application.getSellerRequestReference(),
                        businessProfile
                );

        ApplicationSubmittedEvent payload =
                new ApplicationSubmittedEvent(
                        eventId,
                        APPLICATION_SUBMITTED_EVENT,
                        EVENT_SCHEMA_VERSION,
                        occurredAt,
                        EVENT_PRODUCER,
                        application.getPublicApplicationId(),
                        aggregateVersion,
                        correlationId,
                        data,
                        actor,
                        originActor
                );

        String payloadJson =
                objectMapper.writeValueAsString(payload);

        OutboxEventEntity outboxEvent =
                OutboxEventEntity.pending(
                        eventId,
                        APPLICATION_AGGREGATE,
                        application.getPublicApplicationId(),
                        APPLICATION_SUBMITTED_EVENT,
                        EVENT_VERSION,
                        payloadJson
                );

        outboxEventRepository.saveAndFlush(outboxEvent);
    }
    // --------------------------------------------------
    // REQUEST VALIDATION
    // --------------------------------------------------

    private void validateIdempotencyKey(String idempotencyKey) {

        if (idempotencyKey == null
                || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key header is required");
        }

        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must not exceed 255 characters");
        }
    }

    private void validateBusinessRules(ApplicationRequest request) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Application request cannot be null");
        }

        if (request.marketplaceId() == null) {
            throw new IllegalArgumentException(
                    "Marketplace ID is required");
        }

        if (request.marketplaceRequestId() == null
                || request.marketplaceRequestId().isBlank()) {
            throw new IllegalArgumentException(
                    "Marketplace request ID is required");
        }

        if (request.requestedAmount() == null
                || request.requestedAmount().isBlank()) {
            throw new IllegalArgumentException(
                    "Requested amount is required");
        }

        BigDecimal amount;

        try {
            amount = new BigDecimal(request.requestedAmount());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Requested amount must be a valid decimal");
        }

        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Requested amount must be greater than zero");
        }

        if (!DEFAULT_CURRENCY.equals(request.currency())) {
            throw new IllegalArgumentException(
                    "Only INR is supported");
        }

        if (request.loanPurpose() == null) {
            throw new IllegalArgumentException(
                    "Loan purpose is required");
        }

        if (request.businessProfile() == null
                || request.businessProfile().businessStartDate() == null) {
            throw new IllegalArgumentException(
                    "Business profile and start date are required");
        }

        LocalDate businessStartDate =
                request.businessProfile().businessStartDate();

        if (businessStartDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException(
                    "Business start date cannot be in the future");
        }

        if (request.initialSharingConsent() == null
                || request.initialSharingConsent().authorisedAt() == null) {
            throw new IllegalArgumentException(
                    "Initial sharing consent and authorization time are required");
        }

        Instant consentTime =
                request.initialSharingConsent()
                        .authorisedAt()
                        .toInstant();

        if (consentTime.isAfter(Instant.now())) {
            throw new IllegalArgumentException(
                    "Consent authorization time cannot be in the future");
        }
    }

    // --------------------------------------------------
    // HELPERS
    // --------------------------------------------------

    private String generateApplicationReference() {

        return "CF-"
                + UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, 20)
                        .toUpperCase(Locale.ROOT);
    }

    private com.credflow.application.dto.Phase0Details phase0(ApplicationEntity entity){return entity.getPhase0Json()==null?null:objectMapper.readValue(entity.getPhase0Json(),com.credflow.application.dto.Phase0Details.class);}
    private ApplicationResponse toResponse(ApplicationEntity entity) {

        return new ApplicationResponse(
                entity.getPublicApplicationId(),
                entity.getApplicationReference(),
                entity.getSellerId(),
                entity.getMarketplaceId(),
                entity.getRequestedAmount().setScale(2).toPlainString(),
                entity.getCurrency(),
                com.credflow.application.dto.LoanPurpose.valueOf(
                        entity.getLoanPurpose()),
                entity.getStatus(),
                entity.getApplicationRevision(),
                entity.getVersion() == null ? 0 : entity.getVersion(),
                entity.getWorkflowVersion(),
                new ApplicationResponse.BusinessProfileResponse(
                        entity.getDisplayName(),
                        entity.getBusinessName(),
                        entity.getBusinessStartDate(),
                        phase0(entity)
                )
        );
    }
    public ApplicationContextResponse getApplicationContext(
            String publicApplicationId,
            Jwt jwt) {

        if (jwt == null) {
            throw new AccessDeniedException(
                    "Service authentication is required"
            );
        }

        List<String> roles = jwt.getClaimAsStringList("roles");

        boolean allowedService = roles != null
                && (roles.contains("ELIGIBILITY_SERVICE")
                    || roles.contains("VERIFICATION_SERVICE"));

        if (!allowedService) {
            throw new AccessDeniedException(
                    "You are not allowed to access application context"
            );
        }

        if (publicApplicationId == null
                || publicApplicationId.isBlank()) {

            throw new IllegalArgumentException(
                    "Application ID cannot be blank"
            );
        }

        UUID parsedId;

        try {
            parsedId = UUID.fromString(publicApplicationId);

        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Application ID must be a valid UUID"
            );
        }

        ApplicationEntity entity = applicationRepository
                .findByPublicApplicationId(parsedId.toString())
                .orElseThrow(() -> new ApplicationNotFoundException(
                        "Application not found"
                ));

        return new ApplicationContextResponse(
                entity.getPublicApplicationId(),
                entity.getApplicationRevision(),
                entity.getSellerId(),
                entity.getMarketplaceId(),
                entity.getRequestedAmount()
                        .setScale(2, RoundingMode.UNNECESSARY)
                        .toPlainString(),
                entity.getCurrency(),
                entity.getLoanPurpose(),
                new ApplicationContextResponse.BusinessProfileResponse(
                        entity.getDisplayName(),
                        entity.getBusinessName(),
                        entity.getBusinessStartDate(),
                        phase0(entity)
                ),
                entity.getStatus()
        );
    }
   
    public OwnershipCheckResponse checkApplicationOwnership(
            String publicApplicationId,
            String sellerId,
            Jwt jwt) {

        // Reuse the service-role authorization, UUID validation,
        // and trusted application lookup already implemented.
        ApplicationContextResponse context =
                getApplicationContext(publicApplicationId, jwt);

        if (sellerId == null || sellerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Seller ID is required");
        }

        String storedSellerId = context.sellerId();

        boolean ownsApplication =
                storedSellerId != null
                && !storedSellerId.isBlank()
                && storedSellerId.equals(sellerId);

        return new OwnershipCheckResponse(
                context.applicationId(),
                ownsApplication
        );
    }
}
