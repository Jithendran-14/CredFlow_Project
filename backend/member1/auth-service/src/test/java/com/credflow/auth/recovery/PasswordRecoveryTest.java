package com.credflow.auth.recovery;

import java.util.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mail.MailSendException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordRecoveryTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final PasswordEncoder encoder=mock(PasswordEncoder.class);
    final RecoveryMail mail=mock(RecoveryMail.class);
    final RecoveryLimits limits=mock(RecoveryLimits.class);
    PasswordRecoveryService service(){when(limits.allow(anyString(),anyString(),nullable(String.class))).thenReturn(true);return new PasswordRecoveryService(db,encoder,mail,limits,true);}
    Map<String,Object> user(){return new HashMap<>(Map.of("USER_ID",7L,"EMAIL_ADDRESS","seller@example.test","USER_ROLE","SELLER","ACCOUNT_STATUS","ACTIVE","PROFILE_VERSION",1,"PASSWORD_HASH","old-hash"));}
    @Test void accountErrorsAreReturnedWithExplicitCodes(){var e=new RecoveryErrors();var response=e.status(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"User does not exist. Check your username and try again."));assertEquals(404,response.getStatusCode().value());assertEquals("no-store",response.getHeaders().getCacheControl());assertTrue(response.getBody().toString().contains("RESET_USER_NOT_FOUND"));assertTrue(response.getBody().toString().contains("User does not exist"));assertEquals(403,e.status(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Inactive")).getStatusCode().value());}
    @Test void disabledRecoveryFailsClosed(){var s=new PasswordRecoveryService(db,encoder,mail,limits,false);assertEquals(503,assertThrows(ResponseStatusException.class,()->s.request("seller","ip")).getStatusCode().value());verifyNoInteractions(db,mail,limits);}
    @Test void unknownAccountSendsNothing() throws Exception {var s=service();var ex=assertThrows(ResponseStatusException.class,()->s.request("unknown","ip"));assertEquals(404,ex.getStatusCode().value());assertTrue(ex.getReason().contains("User does not exist"));verifyNoInteractions(mail);verify(mail,never()).send(anyString(),anyString());verify(db,never()).update(anyString(),any(Object[].class));}
    @Test void disabledAccountSendsNothing() throws Exception {var s=service();var u=user();u.put("ACCOUNT_STATUS","DISABLED");when(db.queryForList(contains("LOWER(USERNAME)"),eq("seller"))).thenReturn(List.of(u));assertEquals(403,assertThrows(ResponseStatusException.class,()->s.request("SELLER","ip")).getStatusCode().value());verifyNoInteractions(mail);}
    @Test void machineIdentityHasNoRecovery() throws Exception {var s=service();var u=user();u.put("USER_ROLE","APPLICATION_SERVICE");when(db.queryForList(contains("LOWER(USERNAME)"),eq("seller"))).thenReturn(List.of(u));assertEquals(403,assertThrows(ResponseStatusException.class,()->s.request("seller","ip")).getStatusCode().value());verifyNoInteractions(mail);}
    @Test void onlyTokenHashIsStoredAndTrustedPortalUsed() throws Exception {var s=service();when(db.queryForList(contains("LOWER(USERNAME)"),eq("seller"))).thenReturn(List.of(user()));s.request("seller","ip");var link=org.mockito.ArgumentCaptor.forClass(String.class);verify(mail).send(eq("seller@example.test"),link.capture());assertTrue(link.getValue().startsWith("http://localhost:5175/#/reset-password/"));String token=link.getValue().substring(link.getValue().lastIndexOf('/')+1);assertEquals(43,token.length());verify(db).update(contains("INSERT INTO CF_PASSWORD_RESET_TOKENS"),eq(PasswordRecoveryService.hash(token)),eq(7L),eq("seller@example.test"),eq(1));}
    @Test void knownAccountMailOutagePreventsTokenChanges() throws Exception {var s=service();when(db.queryForList(contains("LOWER(USERNAME)"),eq("seller"))).thenReturn(List.of(user()));doThrow(new IOException("offline")).when(mail).available();assertThrows(IOException.class,()->s.request("seller","ip"));verify(db,never()).update(anyString(),any(Object[].class));}
    @Test void smtpFailurePropagatesForTransactionRollback() {var s=service();when(db.queryForList(contains("LOWER(USERNAME)"),eq("seller"))).thenReturn(List.of(user()));doThrow(new MailSendException("offline")).when(mail).send(anyString(),anyString());assertThrows(MailSendException.class,()->s.request("seller","ip"));}
    @Test void rateLimitDeniesBeforeLookup() {var s=service();when(limits.allow(anyString(),anyString(),nullable(String.class))).thenReturn(false);assertEquals(429,assertThrows(ResponseStatusException.class,()->s.request("seller","ip")).getStatusCode().value());verifyNoInteractions(db,mail);}
    @Test void mismatchedPasswordsDoNotReadToken() {var s=service();assertEquals(400,assertThrows(ResponseStatusException.class,()->s.reset("x".repeat(43),"Password12345","Different1234","ip")).getStatusCode().value());verifyNoInteractions(db,encoder);}
    @Test void unknownResetTokenNeverChangesPassword() {var s=service();assertEquals(400,assertThrows(ResponseStatusException.class,()->s.reset("x".repeat(43),"Password12345","Password12345","ip")).getStatusCode().value());verifyNoInteractions(encoder);}
    @Test void portalMappingExcludesUnknownLenders(){assertEquals(5174,PasswordRecoveryService.portal("MARKETPLACE",""));assertEquals(5176,PasswordRecoveryService.portal("LENDER_OFFICER","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1"));assertEquals(5176,PasswordRecoveryService.portal("LENDER_OFFICER","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2"));assertEquals(5178,PasswordRecoveryService.portal("ADMIN",""));assertEquals(0,PasswordRecoveryService.portal("LENDER_OFFICER","untrusted"));}
    @Test void errorResponsesAreSafeAndPreserveRetryAdvice(){var e=new RecoveryErrors();var r=e.mail(new MailSendException("secret token"));assertEquals(503,r.getStatusCode().value());assertFalse(r.getBody().toString().contains("secret token"));assertEquals("900",e.status(new ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"Wait")).getHeaders().getFirst("Retry-After"));assertEquals(400,e.validation(new IllegalArgumentException()).getStatusCode().value());assertEquals(503,e.database(new org.springframework.dao.DataAccessResourceFailureException("secret connection details")).getStatusCode().value());assertFalse(e.database(new org.springframework.dao.DataAccessResourceFailureException("secret connection details")).getBody().toString().contains("secret connection details"));}
}
