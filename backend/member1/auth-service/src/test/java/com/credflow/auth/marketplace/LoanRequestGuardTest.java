package com.credflow.auth.marketplace;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class LoanRequestGuardTest {
 JdbcTemplate db=mock(JdbcTemplate.class);ActiveApplicationClient apps=mock(ActiveApplicationClient.class);
 LoanRequestService service=new LoanRequestService(db,new ObjectMapper(),apps);
 Jwt seller=Jwt.withTokenValue("t").header("alg","RS256").subject("person").claim("roles",List.of("SELLER")).claim("sellerId","seller").build();
 void profile(){when(db.queryForList("SELECT SELLER_ID FROM CF_SELLER_PROFILES WHERE SELLER_ID=? FOR UPDATE","seller")).thenReturn(List.of(Map.of("SELLER_ID","seller")));}
 @Test void activeApplicationIsRejectedBeforeQueueInsert(){profile();doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Active application exists")).when(apps).requireNoActive(seller,"WORKING_CAPITAL");var ex=assertThrows(ResponseStatusException.class,()->service.submit(new LoanRequestInput("100000.00","WORKING_CAPITAL",true,new Phase0Details(12,"500000.00","100000.00","10000.00",java.time.LocalDate.of(1990,1,1),"MOCK_ID","TEST-IDENTITY001",true,true)),UUID.randomUUID().toString(),seller));assertEquals(409,ex.getStatusCode().value());verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void existingPendingRequestAlsoBlocksAnotherKey(){profile();when(db.queryForObject(contains("COUNT(*) FROM CF_MARKETPLACE_LOAN_REQUESTS"),eq(Integer.class),eq("seller"),eq("WORKING_CAPITAL"))).thenReturn(1);var ex=assertThrows(ResponseStatusException.class,()->service.submit(new LoanRequestInput("100000.00","WORKING_CAPITAL",true,new Phase0Details(12,"500000.00","100000.00","10000.00",java.time.LocalDate.of(1990,1,1),"MOCK_ID","TEST-IDENTITY001",true,true)),UUID.randomUUID().toString(),seller));assertEquals(409,ex.getStatusCode().value());assertTrue(ex.getReason().contains("awaiting submission"));}
 @Test void missingProfileNeverCreatesRequest(){assertThrows(ResponseStatusException.class,()->service.submit(new LoanRequestInput("100000.00","WORKING_CAPITAL",true,new Phase0Details(12,"500000.00","100000.00","10000.00",java.time.LocalDate.of(1990,1,1),"MOCK_ID","TEST-IDENTITY001",true,true)),UUID.randomUUID().toString(),seller));verifyNoInteractions(apps);}
}
