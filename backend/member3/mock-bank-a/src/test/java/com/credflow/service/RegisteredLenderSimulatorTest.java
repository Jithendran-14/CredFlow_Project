package com.credflow.service;
import java.util.*;import org.junit.jupiter.api.Test;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.test.util.ReflectionTestUtils;import static org.mockito.Mockito.*;import static org.junit.jupiter.api.Assertions.*;
class RegisteredLenderSimulatorTest {
 @Test void bankCodesResolveOnlyRegisteredLenders(){var service=new BankASimulatorService(null,null,"integration-service","bank-a",UUID.randomUUID().toString(),"http://localhost:8087");var db=mock(JdbcTemplate.class);service.registry=db;String lender=UUID.randomUUID().toString();when(db.queryForList(anyString(),eq(String.class),eq("new-bank"))).thenReturn(List.of(lender));assertEquals(lender,ReflectionTestUtils.invokeMethod(service,"lender","new-bank"));assertThrows(Exception.class,()->ReflectionTestUtils.invokeMethod(service,"lender","unknown-bank"));}
}
