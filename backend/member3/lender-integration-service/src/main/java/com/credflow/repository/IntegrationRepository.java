package com.credflow.repository;

import com.credflow.entity.IntegrationCallback;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Own-schema persistence and query definitions; uses the caller's transaction.
 */
@Repository
public class IntegrationRepository {

    private final EntityManager em;

    public IntegrationRepository(EntityManager em) {
        this.em = em;
    }

    public <T> T find(Class<T> type, Object id) {
        return em.find(type, id);
    }

    public <T> T find(Class<T> type, Object id, LockModeType lock) {
        return em.find(type, id, lock);
    }

    public void save(Object row) {
        em.persist(row);
    }

    public void flush() {
        em.flush();
    }

    public void refresh(Object row, LockModeType lock) {
        em.refresh(row, lock);
    }

    public jakarta.persistence.TypedQuery<IntegrationCallback> callbackAtSequenceQuery() {
        return em.createQuery("select c from IntegrationCallback c where " + "c.submissionId=:s and c.lenderSequence=:n", IntegrationCallback.class);
    }

    public jakarta.persistence.TypedQuery<Long> countCallbacksAtSequenceQuery() {
        return em.createQuery("select count(c) from IntegrationCallback c " + "where c.submissionId=:s " + "and c.lenderSequence=:n", Long.class);
    }

    public jakarta.persistence.TypedQuery<UUID> dueTaskIdsQuery() {
        return em.createQuery("select t.taskId from IntegrationTask t where " + "t.status in ('PENDING','WORKING') " + "and t.nextAttemptAt<=:now " + "and (t.operation='CANCEL' or not exists " + "(select x.taskId from IntegrationTask x where " + "x.submissionId=t.submissionId " + "and x.position<t.position " + "and x.status in ('PENDING','WORKING'))) " + "order by case when t.operation='CANCEL' " + "then 0 else 1 end,t.position", UUID.class);
    }

    public jakarta.persistence.TypedQuery<Long> countEarlierTasksQuery() {
        return em.createQuery("select count(x) from IntegrationTask x where " + "x.submissionId=:s and x.position<:p " + "and x.status in ('PENDING','WORKING')", Long.class);
    }

    public jakarta.persistence.TypedQuery<UUID> dueCallbackIdsQuery() {
        return em.createQuery("select c.eventId from IntegrationCallback c where " + "c.status='PENDING' and c.nextAttemptAt<=:now " + "order by c.nextAttemptAt,c.receivedAt", UUID.class);
    }

    public jakarta.persistence.Query nextDeliveryPositionQuery() {
        return em.createNativeQuery("select CF_M3_DELIVERY_SEQ.nextval from dual");
    }
}
