package com.credflow.simulator.financial;
import com.fasterxml.jackson.databind.*;
import java.time.*;import java.util.*;import java.nio.charset.StandardCharsets;import java.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;

class LegacyDatasetCompatibilityTest {
 String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
 @Test void newRequestsAvoidLegacySchemaCollisionAndOldReceiptRemainsUnchanged()throws Exception {
  var mapper=new ObjectMapper();var records=mock(FinancialMockRecords.class);var tx=mock(TransactionTemplate.class);var store=new HashMap<String,FinancialMockRecord>();
  when(records.findById(anyString())).thenAnswer(a->Optional.ofNullable(store.get(a.getArgument(0))));
  when(records.saveAndFlush(any())).thenAnswer(a->{FinancialMockRecord record=a.getArgument(0);store.put(record.key,record);return record;});
  when(tx.execute(any())).thenAnswer(a->((TransactionCallback<?>)a.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
  var end=YearMonth.now(ZoneOffset.UTC).atDay(1);var start=end.minusMonths(3);var asOf=LocalDate.now(ZoneOffset.UTC);String fixture="DEMO-FINANCIAL-GOOD-001";
  String legacyKey="dataset:"+hash(fixture+"|"+start+"|"+end);var legacy=mapper.createObjectNode().put("dataReference","MOCK-"+legacyKey.substring(8)).put("dataVersion",1);
  store.put(legacyKey,new FinancialMockRecord(legacyKey,legacyKey.substring(8),legacy.toString()));
  UUID oldId=UUID.randomUUID();var oldReceipt=mapper.createObjectNode().put("requestId",oldId.toString());oldReceipt.set("dataset",legacy.deepCopy());
  store.put("request:"+oldId,new FinancialMockRecord("request:"+oldId,hash(fixture+"|"+FinancialMockService.SCOPE+"|"+asOf+"|"+start+"|"+end),oldReceipt.toString()));
  var service=new FinancialMockService(records,mapper,tx);assertEquals(oldReceipt,service.retrieve(new FinancialMockService.Request(oldId,fixture,FinancialMockService.SCOPE,asOf,start,end),oldId.toString()));
  UUID fresh=UUID.randomUUID();var result=service.retrieve(new FinancialMockService.Request(fresh,fixture,FinancialMockService.SCOPE,asOf,start,end),fresh.toString()).path("dataset");
  assertEquals(2,result.path("dataVersion").asInt());assertEquals("GOOD",result.path("creditScoreBand").asText());assertNotEquals(legacy.path("dataReference"),result.path("dataReference"));
  UUID second=UUID.randomUUID();assertEquals(result,service.retrieve(new FinancialMockService.Request(second,fixture,FinancialMockService.SCOPE,asOf,start,end),second.toString()).path("dataset"));
  assertEquals(legacy.toString(),store.get(legacyKey).payload);assertEquals(oldReceipt.toString(),store.get("request:"+oldId).payload);
 }
}
