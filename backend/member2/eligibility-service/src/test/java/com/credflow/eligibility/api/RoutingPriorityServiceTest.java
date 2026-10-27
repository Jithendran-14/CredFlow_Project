package com.credflow.eligibility.api;

import java.util.*;
import com.credflow.platform.persistence.DatabaseAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RoutingPriorityServiceTest {
 final DatabaseAccess db=mock(DatabaseAccess.class);
 final LenderRulesService service=new LenderRulesService(db,new ObjectMapper());
 final UUID id=UUID.randomUUID(); final String lender=UUID.randomUUID().toString();
 Jwt identity(String role){return Jwt.withTokenValue("unit").header("alg","RS256").subject("actor").claim("roles",List.of(role)).claim("lenderId",lender).build();}
 Map<String,Object> row(int version,int priority){
  var r=new HashMap<String,Object>();r.put("PRODUCT_ID",id.toString());r.put("LENDER_ID",lender);r.put("PRODUCT_NAME","Product");r.put("RULE_VERSION",version);r.put("ROUTING_PRIORITY",priority);r.put("ACTIVE",1);
  for(String key:List.of("MIN_AVERAGE_NET","MAX_RETURN_PERCENT","MIN_AMOUNT","MAX_AMOUNT","SALES_MULTIPLIER","MAX_REPAYMENT_PERCENT","ASSESSMENT_APR_PERCENT"))r.put(key,new java.math.BigDecimal("10"));
  r.put("MIN_AGE_MONTHS",12);r.put("MIN_CREDIT_SCORE_BAND","GOOD");return r;
 }
 @Test void lendersAndSellersCannotReadOrWriteAdminPriority(){
  for(String role:List.of("LENDER_OFFICER","SELLER","MARKETPLACE")){
   assertEquals(403,assertThrows(ResponseStatusException.class,()->service.routingPriorities(identity(role))).getStatusCode().value());
   assertEquals(403,assertThrows(ResponseStatusException.class,()->service.updateRoutingPriority(id,new RoutingPriorityInput(1,5,"Change"),UUID.randomUUID().toString(),identity(role))).getStatusCode().value());
  }
  verifyNoInteractions(db);
 }
 @Test void lenderOverpostingPriorityIsDeniedBeforeAnyDatabaseReadOrWrite(){
  var input=new LenderRulesInput(1,"Product",12,"1000","5","1","10000","1",5,true,Set.of("WORKING_CAPITAL"),"Change");
  assertEquals(403,assertThrows(ResponseStatusException.class,()->service.update(id,input,UUID.randomUUID().toString(),identity("LENDER_OFFICER"))).getStatusCode().value());
  verifyNoInteractions(db);
 }
 @Test void adminListExposesRoutingFieldsWithoutEligibilityRules(){
  when(db.queryForList(anyString())).thenReturn(List.of(row(2,5)));
  var result=(Map<?,?>)service.routingPriorities(identity("ADMIN"));var item=(Map<?,?>)((List<?>)result.get("items")).getFirst();
  assertEquals(5,item.get("routingPriority"));assertEquals(6,item.size());assertFalse(item.containsKey("minAmount"));
 }
 @Test void lenderListOmitsPriority(){
  when(db.queryForList(contains("WHERE R.lender_id=?"),eq(lender))).thenReturn(List.of(row(2,5)));
  var result=(Map<?,?>)service.list(identity("LENDER_OFFICER"));var item=(Map<?,?>)((List<?>)result.get("items")).getFirst();
  assertFalse(item.containsKey("routingPriority"));
 }
 @Test void staleAdminVersionCannotOverwriteConcurrentLenderRules(){
  when(db.queryForList(anyString(),eq(Integer.class),eq(id.toString()))).thenReturn(List.of(2));
  assertEquals(409,assertThrows(ResponseStatusException.class,()->service.updateRoutingPriority(id,new RoutingPriorityInput(1,5,"Change"),UUID.randomUUID().toString(),identity("ADMIN"))).getStatusCode().value());
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void lenderRuleSavePreservesExistingAdminPriority(){
  when(db.queryForList(contains("WHERE product_id=? AND lender_id=?"),eq(String.class),eq(id.toString()),eq(lender))).thenReturn(List.of(id.toString()));
  when(db.queryForList(anyString(),eq(Integer.class),eq(id.toString()))).thenReturn(List.of(1));
  when(db.queryForMap(anyString(),eq(id.toString()),any())).thenAnswer(a->row(((Number)a.getArgument(2)).intValue(),17));
  var input=new LenderRulesInput(1,"Product",12,"1000","5","1","10000","1",null,true,Set.of("WORKING_CAPITAL"),"Change");
  service.update(id,input,UUID.randomUUID().toString(),identity("LENDER_OFFICER"));
  var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
  verify(db).update(startsWith("INSERT INTO lender_product_rule"),args.capture());
  assertEquals(17,args.getValue()[10]);
 }
 @Test void adjacentMoveShiftsTheOtherProductWithReason(){
  var first=row(1,1);var other=row(3,2);String otherId=UUID.randomUUID().toString();other.put("PRODUCT_ID",otherId);
  when(db.queryForList(contains("SELECT current_version"),eq(Integer.class),eq(id.toString()))).thenReturn(List.of(1));
  when(db.queryForList(contains("SELECT current_version"),eq(Integer.class),eq(otherId))).thenReturn(List.of(3));
  when(db.queryForList(contains("SELECT R.*"))).thenReturn(List.of(first,other));when(db.queryForMap(anyString(),eq(id.toString()),eq(2))).thenReturn(row(2,2));
  service.updateRoutingPriority(id,new RoutingPriorityInput(1,2,"Prefer second position"),UUID.randomUUID().toString(),identity("ADMIN"));
  verify(db).update(contains("SELECT product_id,?,lender_id"),eq(2),eq(2),eq(id.toString()),eq(1));
  verify(db).update(contains("SELECT product_id,?,lender_id"),eq(4),eq(1),eq(otherId),eq(3));
  verify(db).update(startsWith("INSERT INTO lender_rule_change"),eq(otherId),eq(4),eq("actor"),contains("Automatic reorder: Prefer second position"),anyString(),anyString());
 }
 @Test void blankReasonCannotChangePriority(){assertEquals(400,assertThrows(ResponseStatusException.class,()->service.updateRoutingPriority(id,new RoutingPriorityInput(1,2," "),UUID.randomUUID().toString(),identity("ADMIN"))).getStatusCode().value());verifyNoInteractions(db);}
 @Test void movingFirstToThirdShiftsBothInterveningProducts(){verifyRangeReorder(1,3);}
 @Test void movingThirdToFirstShiftsBothInterveningProducts(){verifyRangeReorder(3,1);}
 private void verifyRangeReorder(int from,int to){
  var rows=new ArrayList<Map<String,Object>>();
  for(int position=1;position<=5;position++){var r=row(position+10,position);r.put("PRODUCT_ID",position==from?id.toString():UUID.randomUUID().toString());rows.add(r);String product=r.get("PRODUCT_ID").toString();when(db.queryForList(contains("SELECT current_version"),eq(Integer.class),eq(product))).thenReturn(List.of(position+10));}
  when(db.queryForList(contains("SELECT R.*"))).thenReturn(rows);when(db.queryForMap(anyString(),eq(id.toString()),eq(from+11))).thenReturn(row(from+11,to));
  service.updateRoutingPriority(id,new RoutingPriorityInput(from+10,to,"Preserve lender order"),UUID.randomUUID().toString(),identity("ADMIN"));
  for(var r:rows){int position=((Number)r.get("ROUTING_PRIORITY")).intValue();String product=r.get("PRODUCT_ID").toString();int version=position+10;if(position>=Math.min(from,to)&&position<=Math.max(from,to)){int expected=position==from?to:position+(to>from?-1:1);verify(db).update(contains("SELECT product_id,?,lender_id"),eq(version+1),eq(expected),eq(product),eq(version));verify(db).update(startsWith("INSERT INTO lender_rule_change"),eq(product),eq(version+1),eq("actor"),contains("Preserve lender order"),argThat((String k)->k.length()<=100),anyString());}else{verify(db,never()).update(contains("SELECT product_id,?,lender_id"),any(),any(),eq(product),any());}}
 }
}

