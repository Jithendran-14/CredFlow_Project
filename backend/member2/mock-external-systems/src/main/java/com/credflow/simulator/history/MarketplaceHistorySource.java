package com.credflow.simulator.history;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
public class MarketplaceHistorySource {
 private final JdbcTemplate jdbc;private final ObjectMapper json;
 public MarketplaceHistorySource(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 public Optional<JsonNode> financial(String dataId){
  var rows=jdbc.query("SELECT PAYLOAD_JSON FROM CF_AUTH.CF_SELLER_BUSINESS_HISTORY WHERE DATA_ID=?",(rs,n)->rs.getString(1),dataId);
  if(rows.isEmpty())return Optional.empty();try{return Optional.of(json.readTree(rows.getFirst()));}catch(Exception ex){throw new IllegalStateException("Invalid saved marketplace history",ex);}
 }
 public Optional<Boolean> businessReviewed(String identity){var rows=jdbc.queryForList("SELECT BUSINESS_REVIEWED FROM CF_AUTH.CF_SELLER_BUSINESS_HISTORY WHERE IDENTITY_FIXTURE_ID=?",Integer.class,identity);return rows.stream().findFirst().map(x->x==1);}
}
