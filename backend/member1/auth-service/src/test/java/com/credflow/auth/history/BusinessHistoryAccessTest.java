package com.credflow.auth.history;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;import static org.junit.jupiter.api.Assertions.*;
class BusinessHistoryAccessTest {
 Jwt token(String role){return Jwt.withTokenValue("test").header("alg","RS256").subject(UUID.randomUUID().toString()).claim("roles",List.of(role)).claim("marketplaceId","market").build();}
 @Test void sellerCannotManageHistory(){var db=mock(JdbcTemplate.class);assertThrows(ResponseStatusException.class,()->new BusinessHistoryService(db,new ObjectMapper()).read("seller",token("SELLER")));verifyNoInteractions(db);}
 @Test void anotherMarketplaceCannotReadOrWriteSeller(){var db=mock(JdbcTemplate.class);var svc=new BusinessHistoryService(db,new ObjectMapper());var t=token("MARKETPLACE");var ex=assertThrows(ResponseStatusException.class,()->svc.read("seller",t));assertEquals(404,ex.getStatusCode().value());verify(db).queryForList(contains("WHERE SELLER_ID=? AND MARKETPLACE_ID=?"),eq("seller"),eq("market"));assertThrows(ResponseStatusException.class,()->svc.save("seller",null,UUID.randomUUID().toString(),t));verify(db,never()).update(anyString(),any(Object[].class));}
}
