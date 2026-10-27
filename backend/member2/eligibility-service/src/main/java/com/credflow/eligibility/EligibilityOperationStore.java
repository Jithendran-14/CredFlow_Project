package com.credflow.eligibility;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import com.credflow.platform.persistence.DatabaseAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable intake. No worker fabricates upstream context or completes these rows. */
@Service
public class EligibilityOperationStore {
    public record Command(UUID evaluationRequestId, UUID applicationId, int applicationRevision,
            UUID verificationId, LocalDate asOfDate, String authorisedRetrievalReference) {}
    public record Operation(UUID operationId, UUID applicationId, int applicationRevision, String status,
            String createdAt, UUID eligibilityResultId, String errorCode) {}
    public static class OperationConflictException extends IllegalArgumentException {
        public OperationConflictException(){super("Evaluation request was already used with different inputs");}
    }
    private final DatabaseAccess database;
    public EligibilityOperationStore(DatabaseAccess database){this.database=database;}
    @Transactional
    public Operation enqueue(Command c,String callerClientId,String correlationId) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Durable intake requires a transaction");
        Objects.requireNonNull(c);Objects.requireNonNull(c.evaluationRequestId());Objects.requireNonNull(c.applicationId());
        Objects.requireNonNull(c.verificationId());Objects.requireNonNull(c.asOfDate());
        if(c.applicationRevision()<1 || c.authorisedRetrievalReference()==null || c.authorisedRetrievalReference().isBlank()
                || c.authorisedRetrievalReference().length()>255 || callerClientId==null || callerClientId.isBlank()
                || callerClientId.length()>100 || correlationId==null || !correlationId.matches("[A-Za-z0-9._:-]{1,100}"))
            throw new IllegalArgumentException("Invalid durable operation inputs");
        String hash=hash(c);
        var old=byRequest(c.evaluationRequestId());
        if(!old.isEmpty())return replay(old.getFirst(),hash,callerClientId);
        UUID id=UUID.randomUUID();
        try {
            database.update("INSERT INTO eligibility_operation(operation_id,evaluation_request_id,application_id,application_revision,verification_id,as_of_date,retrieval_reference,request_hash,caller_client_id,correlation_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id.toString(),c.evaluationRequestId().toString(),c.applicationId().toString(),c.applicationRevision(),
                c.verificationId().toString(),java.sql.Date.valueOf(c.asOfDate()),c.authorisedRetrievalReference(),hash,callerClientId,correlationId);
        }catch(DuplicateKeyException duplicate){
            old=byRequest(c.evaluationRequestId());if(old.isEmpty())throw duplicate;return replay(old.getFirst(),hash,callerClientId);
        }
        return find(id,callerClientId).orElseThrow();
    }
    @Transactional(readOnly=true)
    public Optional<Operation> find(UUID id,String callerClientId) {
        return database.query("SELECT operation_id,application_id,application_revision,status,created_at,eligibility_result_id,error_code FROM eligibility_operation WHERE operation_id=? AND caller_client_id=?",
            (rs,n)->new Operation(UUID.fromString(rs.getString("operation_id")),UUID.fromString(rs.getString("application_id")),
                rs.getInt("application_revision"),rs.getString("status"),rs.getObject("created_at",OffsetDateTime.class).toInstant().toString(),
                rs.getString("eligibility_result_id")==null?null:UUID.fromString(rs.getString("eligibility_result_id")),rs.getString("error_code")),
            id.toString(),callerClientId).stream().findFirst();
    }
    private List<Map<String,Object>> byRequest(UUID id){return database.queryForList("SELECT operation_id,request_hash,caller_client_id FROM eligibility_operation WHERE evaluation_request_id=?",id.toString());}
    private Operation replay(Map<String,Object> old,String hash,String caller) {
        if(!hash.equals(old.get("REQUEST_HASH")) || !caller.equals(old.get("CALLER_CLIENT_ID")))throw new OperationConflictException();
        return find(UUID.fromString((String)old.get("OPERATION_ID")),caller).orElseThrow();
    }
    private String hash(Command c) {
        try {
            String value=String.join("|",c.evaluationRequestId().toString(),c.applicationId().toString(),Integer.toString(c.applicationRevision()),
                c.verificationId().toString(),c.asOfDate().toString(),c.authorisedRetrievalReference());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
