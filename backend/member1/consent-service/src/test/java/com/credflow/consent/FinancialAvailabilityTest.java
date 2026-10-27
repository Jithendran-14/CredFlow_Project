package com.credflow.consent;
import com.credflow.consent.controller.FinancialAvailabilityController;
import com.credflow.consent.client.ApplicationOwnershipClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FinancialAvailabilityTest {
 @Test void absenceIsExplicitAndOwnershipIsCheckedFirst(){
  var owner=mock(ApplicationOwnershipClient.class);var db=mock(JdbcTemplate.class);var c=new FinancialAvailabilityController(owner,db);var id=UUID.randomUUID();
  var jwt=Jwt.withTokenValue("token").header("alg","RS256").subject("person").claim("sellerId","seller").claim("roles",List.of("SELLER")).build();
  when(db.queryForObject(anyString(),eq(Integer.class),eq("seller"))).thenReturn(0);
  var result=(Map<?,?>)c.read(id,jwt).getBody();assertEquals(false,result.get("connected"));assertEquals("FINANCIAL_HISTORY_NOT_CONNECTED",result.get("reasonCode"));
  var order=inOrder(owner,db);order.verify(owner).requireOwnership(id,"seller");order.verify(db).queryForObject(anyString(),eq(Integer.class),eq("seller"));
 }
 @Test void anotherSellerCannotDiscoverMapping(){
  var owner=mock(ApplicationOwnershipClient.class);var db=mock(JdbcTemplate.class);var id=UUID.randomUUID();doThrow(new org.springframework.security.access.AccessDeniedException("denied")).when(owner).requireOwnership(id,"other");
  var jwt=Jwt.withTokenValue("token").header("alg","RS256").subject("person").claim("sellerId","other").claim("roles",List.of("SELLER")).build();
  assertThrows(org.springframework.security.access.AccessDeniedException.class,()->new FinancialAvailabilityController(owner,db).read(id,jwt));verifyNoInteractions(db);
 }
}
