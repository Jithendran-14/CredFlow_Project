package com.credflow.auth.service;
import com.credflow.auth.entity.*;
import com.credflow.auth.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MarketplaceClientContractTest {
 static final String MARKET="11111111-1111-4111-8111-111111111111";
 private AuthUser user(){var u=new AuthUser();ReflectionTestUtils.setField(u,"userId",9L);ReflectionTestUtils.setField(u,"publicUserId","marketplace-client");u.setUsername("marketplace-client");u.setUserRole("MARKETPLACE");u.setMarketplaceId(MARKET);u.setAccountStatus("ACTIVE");u.setPasswordHash("hash");return u;}
 private String issue(String binding,String scope)throws Exception{
  var users=mock(AuthUserRepository.class);var clients=mock(AuthServiceClientRepository.class);var encoder=mock(PasswordEncoder.class);var jwt=mock(JwtService.class);
  var c=AuthServiceClient.class.getDeclaredConstructor();c.setAccessible(true);var client=c.newInstance();String id="marketplace-submission-"+MARKET;
  ReflectionTestUtils.setField(client,"userId",9L);ReflectionTestUtils.setField(client,"clientId",id);ReflectionTestUtils.setField(client,"scopes",scope);
  var u=user();u.setMarketplaceId(binding);when(clients.findByClientId(id)).thenReturn(Optional.of(client));when(users.findById(9L)).thenReturn(Optional.of(u));when(encoder.matches("private-test-secret","hash")).thenReturn(true);
  when(jwt.generateClientCredentialsToken(u,id,"application.submit")).thenReturn("scoped-token");
  var form=new LinkedMultiValueMap<String,String>();form.add("grant_type","client_credentials");form.add("scope","application.submit");
  var service=new OAuthTokenService(clients,users,encoder,jwt);String auth="Basic "+Base64.getEncoder().encodeToString((id+":private-test-secret").getBytes(StandardCharsets.ISO_8859_1));
  return service.issueToken(auth,form).access_token();
 }
 @Test void registeredMarketplaceClientCanSubmitForItsOwnMarketplace()throws Exception{assertEquals("scoped-token",issue(MARKET,"application.submit"));}
 @Test void clientCannotImpersonateAnotherMarketplace(){assertThrows(Exception.class,()->issue("22222222-2222-4222-8222-222222222222","application.submit"));}
 @Test void marketplaceClientCannotAcquireOtherMachineGrants(){assertThrows(Exception.class,()->issue(MARKET,"lender.command"));}
 @Test void issuedMarketplaceTokenCarriesOnlyStoredMarketplaceBinding(){
  var encoder=mock(JwtEncoder.class);when(encoder.encode(any())).thenReturn(Jwt.withTokenValue("signed").header("alg","RS256").subject("client").build());
  new JwtService(encoder,mock(AuthServiceClientRepository.class),"credflow-auth","credflow-api",15).generateClientCredentialsToken(user(),"marketplace-submission-"+MARKET,"application.submit");
  var capture=ArgumentCaptor.forClass(JwtEncoderParameters.class);verify(encoder).encode(capture.capture());assertEquals(MARKET,capture.getValue().getClaims().getClaim("marketplaceId"));assertEquals(List.of("MARKETPLACE"),capture.getValue().getClaims().getClaim("roles"));assertNull(capture.getValue().getClaims().getClaim("sellerId"));
 }
}
