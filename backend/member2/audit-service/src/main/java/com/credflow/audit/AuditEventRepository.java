package com.credflow.audit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AuditEventRepository extends JpaRepository<AuditEvent,String> {
 List<AuditEvent> findByApplicationIdOrderByOccurredAtAscEventIdAsc(String applicationId);
}
