package com.credflow.application;
import com.credflow.application.service.ApplicationService;
import com.credflow.application.repository.*;
import com.credflow.application.entity.*;
import com.credflow.application.dto.*;
import com.credflow.application.exception.ActiveApplicationException;
import com.credflow.application.util.RequestFingerprint;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ActiveApplicationTest {
 ApplicationRepository apps=mock(ApplicationRepository.class);IdempotencyKeyRepository keys=mock(IdempotencyKeyRepository.class);SellerMappingRepository mappings=mock(SellerMappingRepository.class);OutboxEventRepository outbox=mock(OutboxEventRepository.class);RequestFingerprint fp=mock(RequestFingerprint.class);
 ApplicationService service=new ApplicationService(apps,keys,mappings,outbox,fp,new ObjectMapper());
 String market="11111111-1111-4111-8111-111111111111";
 Jwt jwt=Jwt.withTokenValue("token").header("alg","RS256").subject("market").claim("marketplaceId",market).claim("client_id","marketplace-submission-"+market).claim("scope","application.submit").claim("roles",List.of("MARKETPLACE")).build();
 ApplicationRequest request(){return new ApplicationRequest(UUID.fromString(market),"REF-1","REQ-1","100000.00","INR",LoanPurpose.WORKING_CAPITAL,new ApplicationRequest.BusinessProfile("Seller","Store",LocalDate.of(2020,1,1)),"seller-request",new ApplicationRequest.InitialSharingConsent("APPLICATION_SHARE_WITH_CREDFLOW","v1",OffsetDateTime.now().minusMinutes(1),"evidence"));}
 @Test void humanMarketplaceCannotCreateApplication(){var human=Jwt.withTokenValue("t").header("alg","RS256").subject("officer").claim("marketplaceId",market).claim("roles",List.of("MARKETPLACE")).build();assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.createApplication(request(),"key",human));verifyNoInteractions(apps,mappings,outbox);}
 @Test void activeSellerBlocksCreationAfterSellerLock(){
  SellerMappingEntity mapping=mock(SellerMappingEntity.class);when(mapping.getSellerId()).thenReturn("seller");when(mappings.findByMarketplaceIdAndMarketplaceSellerId(market,"REF-1")).thenReturn(Optional.of(mapping));
  when(apps.findFirstBySellerIdAndLoanPurposeAndStatusNotInOrderByCreatedAtDesc("seller","WORKING_CAPITAL",ApplicationService.CLOSED_STATUSES)).thenReturn(Optional.of(new ApplicationEntity()));
  assertThrows(ActiveApplicationException.class,()->service.createApplication(request(),"key",jwt));
  var order=inOrder(mappings,apps);order.verify(mappings).findByMarketplaceIdAndMarketplaceSellerId(market,"REF-1");order.verify(mappings).lockSeller("seller");order.verify(apps).findByMarketplaceIdAndMarketplaceRequestId(market,"REQ-1");order.verify(apps).findFirstBySellerIdAndLoanPurposeAndStatusNotInOrderByCreatedAtDesc("seller","WORKING_CAPITAL",ApplicationService.CLOSED_STATUSES);
  verify(apps,never()).save(any());verifyNoInteractions(outbox);
 }
 @Test void activeLookupIsRestrictedToTrustedSeller(){
  Jwt seller=Jwt.withTokenValue("t").header("alg","RS256").subject("person").claim("roles",List.of("SELLER")).claim("sellerId","seller").build();
  assertEquals(false,service.activeApplication(seller).get("active"));verify(apps).findFirstBySellerIdAndStatusNotInOrderByCreatedAtDesc("seller",ApplicationService.CLOSED_STATUSES);
  assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.activeApplication(jwt));
 }
 @Test void onlyCompletedOrClosedStatesReleaseTheGuard(){assertFalse(ApplicationService.CLOSED_STATUSES.contains("ACTION_REQUIRED"));assertFalse(ApplicationService.CLOSED_STATUSES.contains("UNDER_REVIEW"));assertTrue(ApplicationService.CLOSED_STATUSES.contains("OFFER_ACCEPTED"));assertTrue(ApplicationService.CLOSED_STATUSES.contains("CANCELLED"));}
}
