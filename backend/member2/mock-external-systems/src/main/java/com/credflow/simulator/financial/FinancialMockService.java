package com.credflow.simulator.financial;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Fictional provider only. Dataset and request receipts survive restart in the mock's Oracle schema. */
@Service
public class FinancialMockService {
 public static final String SCOPE="FINANCIAL_DATA_FOR_LOAN_ELIGIBILITY";
 public static final Set<String> FIXTURES=Set.of("DEMO-FINANCIAL-GOOD-001","DEMO-FINANCIAL-LOW-001","DEMO-FINANCIAL-MISSING-001","DEMO-FINANCIAL-ZERO-001","DEMO-FINANCIAL-HIGH-RETURNS-001");
 public record Request(UUID requestId,String sellerFixtureId,String dataScope,LocalDate asOfDate,LocalDate periodStart,LocalDate periodEndExclusive){
  @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String field,JsonNode value){throw new IllegalArgumentException("Unknown financial provider request field");}
 }
 private final com.credflow.simulator.history.MarketplaceHistorySource history;
 private final FinancialMockRecords records;private final ObjectMapper json;private final TransactionTemplate tx;
 public FinancialMockService(FinancialMockRecords records,ObjectMapper json,TransactionTemplate tx){this(records,json,tx,null);}
 @org.springframework.beans.factory.annotation.Autowired
 public FinancialMockService(FinancialMockRecords records,ObjectMapper json,TransactionTemplate tx,com.credflow.simulator.history.MarketplaceHistorySource history){this.records=records;this.json=json;this.tx=tx;this.history=history;}
 public JsonNode retrieve(Request r,String key) {
  if(r==null || r.requestId()==null || !r.requestId().toString().equalsIgnoreCase(key==null?"":key)
    || r.sellerFixtureId()==null || r.sellerFixtureId().isBlank() || !SCOPE.equals(r.dataScope()) || r.asOfDate()==null || r.periodStart()==null || r.periodEndExclusive()==null
    || r.asOfDate().isAfter(LocalDate.now(ZoneOffset.UTC))
    || !r.periodEndExclusive().equals(YearMonth.from(r.asOfDate()).atDay(1)) || !r.periodStart().equals(r.periodEndExclusive().minusMonths(3)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid provider request, window or idempotency key");
  if(!FIXTURES.contains(r.sellerFixtureId()) && records.findById("profile:"+r.sellerFixtureId()).isEmpty() && !(r.sellerFixtureId().startsWith("MPH-") && history!=null && history.financial(r.sellerFixtureId()).isPresent()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Financial fixture not found");
  // Unique constraints serialize races across instances; retry after the losing transaction has rolled back.
  try{return tx.execute(s->store(r));}catch(DataIntegrityViolationException conflict){return tx.execute(s->store(r));}
 }
 private JsonNode store(Request r) {
  String requestKey="request:"+r.requestId(),signature=hash(r.sellerFixtureId()+"|"+r.dataScope()+"|"+r.asOfDate()+"|"+r.periodStart()+"|"+r.periodEndExclusive());
  var prior=records.findById(requestKey);
  if(prior.isPresent()) {
   if(!signature.equals(prior.get().hash))throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key was used for another request");
   return parse(prior.get().payload);
  }
  // New requests use the credit-band schema. Existing request receipts above remain immutable.
  String datasetKey="dataset:"+hash("phase0-credit-band-v2|"+r.sellerFixtureId()+"|"+r.periodStart()+"|"+r.periodEndExclusive());
  var dataset=records.findById(datasetKey).orElseGet(()->{
   var generated=new FinancialMockRecord(datasetKey,datasetKey.substring(8),dataset(r,datasetKey).toString());return records.saveAndFlush(generated);
  });
  var response=json.createObjectNode();response.put("requestId",r.requestId().toString());response.put("sellerFixtureId",r.sellerFixtureId());response.put("status","SUCCESS");response.set("dataset",parse(dataset.payload));
  records.saveAndFlush(new FinancialMockRecord(requestKey,signature,response.toString()));return response;
 }
 private ObjectNode dataset(Request r,String key) {
  var d=json.createObjectNode();d.put("sourceProvider","member2-financial-mock");d.put("dataReference","MOCK-"+key.substring(8));d.put("dataVersion",2);
  d.put("retrievedAt",Instant.now().toString());d.put("currency","INR");d.put("periodStart",r.periodStart().toString());d.put("periodEndExclusive",r.periodEndExclusive().toString());
  if(r.sellerFixtureId().startsWith("MPH-")){
   var saved=history.financial(r.sellerFixtureId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Marketplace history not found"));
   d.put("creditScoreBand",saved.path("creditScoreBand").asText("NOT_AVAILABLE"));
   var months=d.putArray("months");for(int i=0;i<3;i++){
    String month=YearMonth.from(r.periodStart()).plusMonths(i).toString();JsonNode found=null;for(var row:saved.path("months"))if(month.equals(row.path("month").asText()))found=row;
    if(found==null){var missing=months.addObject();missing.put("month",month);missing.put("status","MISSING");}else months.add(found.deepCopy());
   }return d;
  }
  var profile=records.findById("profile:"+r.sellerFixtureId());
  if(profile.isPresent()){
   var stored=parse(profile.get().payload);
   if(!r.periodStart().toString().equals(stored.path("periodStart").asText()) || !r.periodEndExclusive().toString().equals(stored.path("periodEndExclusive").asText()))
    throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No financial records are configured for this period");
   d.put("creditScoreBand",stored.path("creditScoreBand").asText("NOT_AVAILABLE"));d.set("months",stored.path("months").deepCopy());return d;
  }
  d.put("creditScoreBand",r.sellerFixtureId().equals("DEMO-FINANCIAL-LOW-001")?"POOR":"GOOD");
  var months=d.putArray("months");
  for(int i=0;i<3;i++) {
   var m=months.addObject();m.put("month",YearMonth.from(r.periodStart()).plusMonths(i).toString());
   boolean missing=r.sellerFixtureId().equals("DEMO-FINANCIAL-MISSING-001") && i==1;
   m.put("status",missing?"MISSING":"AVAILABLE");if(missing)continue;
   String sales="500000.00",returns="10000.00",payout="450000.00";long orders=1000,returned=20;
   if(r.sellerFixtureId().equals("DEMO-FINANCIAL-LOW-001")){sales="30000.00";returns="1000.00";payout="25000.00";orders=100;returned=3;}
   if(r.sellerFixtureId().equals("DEMO-FINANCIAL-ZERO-001")){sales="0.00";returns="0.00";payout="0.00";orders=0;returned=0;}
   if(r.sellerFixtureId().equals("DEMO-FINANCIAL-HIGH-RETURNS-001")){returns="200000.00";payout="250000.00";returned=400;}
   m.put("grossSalesAmount",sales);m.put("returnsAmount",returns);m.put("payoutAmount",payout);m.put("orderCount",orders);m.put("returnedOrderCount",returned);
  }
  return d;
 }
 private JsonNode parse(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalStateException("Stored mock response is invalid",ex);}}
 private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
}
