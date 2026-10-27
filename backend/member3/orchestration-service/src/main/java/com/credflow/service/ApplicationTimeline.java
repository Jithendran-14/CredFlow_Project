package com.credflow.service;
import com.credflow.entity.*;import com.credflow.platform.Platform;import com.credflow.platform.entity.DeliveryRow;
import jakarta.persistence.*;import java.util.*;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
@Service @Transactional(readOnly=true)
public class ApplicationTimeline {
 private final EntityManager em;private final Platform p;private final com.fasterxml.jackson.databind.ObjectMapper json;public ApplicationTimeline(EntityManager em,Platform p,com.fasterxml.jackson.databind.ObjectMapper json){this.em=em;this.p=p;this.json=json;}
 public Map<String,Object> read(UUID id,Jwt jwt){
  var w=em.find(WorkflowRow.class,id);if(w==null)throw new ResponseStatusException(NOT_FOUND,"RESOURCE_NOT_FOUND");p.owner(jwt,w.getSellerSubject(),w.getSellerId(),w.getLenderId(),w.getMarketplaceId(),true);
  var rows=em.createQuery("select d from DeliveryRow d where d.applicationId=:id and d.kind='OUTBOX' and d.target='application.status.changed' order by d.position desc,d.createdAt desc,d.id desc",DeliveryRow.class).setParameter("id",id).setMaxResults(501).getResultList();
  boolean earlier=rows.size()>500;if(earlier)rows=new ArrayList<>(rows.subList(0,500));else rows=new ArrayList<>(rows);Collections.reverse(rows);var items=new ArrayList<Map<String,Object>>();
  for(var row:rows){var event=parse(row.getPayload());var data=event.path("data");String status=data.path("status").asText("");String previous=data.path("previousStatus").asText("");if(status.isBlank()||status.equals(previous))continue;
   items.add(Map.of("eventId",event.path("eventId").asText(row.getId()),"status",status,"previousStatus",previous,"occurredAt",event.path("occurredAt").asText(row.getCreatedAt().toString()),"workflowVersion",data.path("workflowVersion").asLong(),"reasonCode",data.path("reasonCode").asText("")));
  }
  boolean acknowledged=false;if("OFFER_ACCEPTED".equals(w.getStatus())){var offers=em.createQuery("select o.acknowledgement from CfOffer o where o.applicationId=:id and o.status='ACCEPTED'",String.class).setParameter("id",id).getResultList();acknowledged=offers.contains("ACKNOWLEDGED");}
  var next=next(w.getStatus(),w.getDetail(),acknowledged);return Map.of("applicationId",id,"currentStatus",w.getStatus(),"nextActor",next.get("actor"),"nextAction",next.get("action"),"items",items,"hasEarlierTransitions",earlier);
 }
 private com.fasterxml.jackson.databind.JsonNode parse(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalStateException("Stored timeline event is invalid",ex);}}
 public static Map<String,String> next(String status,String reason,boolean acknowledged){String actor,action;
  switch(status){
   case "DRAFT"->{actor="Seller";action="Review and grant the required data-sharing permissions.";}
   case "SUBMITTED"->{actor="CredFlow";action="Run the authorised business identity checks.";}
   case "ELIGIBILITY_CHECKING"->{actor="CredFlow";action="Retrieve permitted financial records and evaluate lender eligibility.";}
   case "READY_FOR_LENDER","SUBMITTING"->{actor="CredFlow";action="Deliver the application to the selected lender.";}
   case "UNDER_REVIEW","MANUAL_REVIEW"->{actor="Lender";action="Review the application and provide a decision, information request or offer.";}
   case "APPROVED"->{boolean preparing="APPROVAL_RECEIVED_AWAITING_OFFER".equals(reason);actor=preparing?"Lender":"Seller";action=preparing?"Prepare complete offer terms for the seller.":"Review the offer amount, interest, tenure and expiry, then accept or decline.";}
   case "OFFER_ACCEPTED"->{actor=acknowledged?"No further action":"Lender";action=acknowledged?"Offer acceptance has been acknowledged by the lender.":"Acknowledge receipt of the seller's offer acceptance.";}
   case "ACTION_REQUIRED"->{boolean consent=reason!=null&&(reason.contains("CONSENT")||reason.equals("FINANCIAL_RETRIEVAL_REFERENCE_REQUIRED"));boolean records=reason!=null&&(reason.contains("FINANCIAL_DATA")||reason.contains("MAPPING")||reason.equals("BUSINESS_DETAILS_REVIEW_REQUIRED"));actor=consent?"Seller":records?"Marketplace":"Administrator";action=consent?"Review the missing or expired permissions.":records?"Review the seller's business details and missing source records.":"Review the requested correction or answer the lender's open information requests.";}
   case "CANCELLED"->{actor="No further action";action="The application was cancelled.";}
   case "REJECTED"->{actor="No further action";action="The lender rejected this application. Review the recorded reason.";}
   case "NOT_ELIGIBLE"->{actor="No further action";action="No configured lender product qualified. Review the eligibility results.";}
   case "CHECK_FAILED"->{actor="No further action";action="A required check failed. Review the recorded reason before starting a new application.";}
   case "OFFER_DECLINED"->{actor="No further action";action="The seller declined the offer.";}
   case "OFFER_EXPIRED"->{actor="No further action";action="The offer expired before acceptance.";}
   default->{actor="CredFlow";action="Processing the current workflow stage.";}
  }return Map.of("actor",actor,"action",action);
 }
}
