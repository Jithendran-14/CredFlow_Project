package com.credflow.repository;

import com.credflow.entity.InformationRow;
import com.credflow.entity.OfferRow;
import com.credflow.entity.SubmissionRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Own-schema persistence and query definitions; uses the caller's transaction.
 */
@Repository
public class WorkflowRepository {

    private final EntityManager em;

    public WorkflowRepository(EntityManager em) {
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

    public jakarta.persistence.TypedQuery<Long> countOpenInformationRequestsQuery() {
        return em.createQuery("select count(q) from CfInformation q where " + "q.applicationId=:a and q.status='OPEN'", Long.class);
    }

    public jakarta.persistence.TypedQuery<Long> countOffersForApplicationQuery() {
        return em.createQuery("select count(o) from CfOffer o where o.applicationId=:a", Long.class);
    }

    public jakarta.persistence.TypedQuery<InformationRow> openInformationRequestsQuery() {
        return em.createQuery("select q from CfInformation q where " + "q.applicationId=:a and q.status='OPEN'", InformationRow.class);
    }

    public jakarta.persistence.TypedQuery<OfferRow> offersForApplicationQuery() {
        return em.createQuery("select o from CfOffer o where o.applicationId=:a order by o.id", OfferRow.class);
    }

    public jakarta.persistence.TypedQuery<Long> countOffersForApplicationPageQuery() {
        return em.createQuery("select count(o) from CfOffer o where o.applicationId=:a", Long.class);
    }

    public jakarta.persistence.TypedQuery<InformationRow> informationRequestsForApplicationQuery() {
        return em.createQuery("select q from CfInformation q where " + "q.applicationId=:a order by q.createdAt, q.id", InformationRow.class);
    }

    public jakarta.persistence.TypedQuery<Long> countInformationRequestsForApplicationQuery() {
        return em.createQuery("select count(q) from CfInformation q where q.applicationId=:a", Long.class);
    }

    public jakarta.persistence.TypedQuery<SubmissionRow> submissionsQuery(String lender, UUID marketplace) {
        return em.createQuery("select s from CfSubmission s" + submissionFilter(lender, marketplace) + " order by s.createdAt desc", SubmissionRow.class);
    }

    public jakarta.persistence.TypedQuery<Long> countSubmissionsQuery(String lender, UUID marketplace) {
        return em.createQuery("select count(s) from CfSubmission s" + submissionFilter(lender, marketplace), Long.class);
    }

    public jakarta.persistence.TypedQuery<OfferRow> activeOffersForApplicationQuery() {
        return em.createQuery("select o from CfOffer o where " + "o.applicationId=:a and o.status='ACTIVE'", OfferRow.class);
    }

    public jakarta.persistence.Query cancelOpenInformationRequestsQuery() {
        return em.createQuery("update CfInformation q set q.status='CANCELLED' " + "where q.applicationId=:a and q.status='OPEN'");
    }

    public jakarta.persistence.TypedQuery<UUID> expiredOfferIdsQuery() {
        return em.createQuery("select o.id from CfOffer o where " + "o.status='ACTIVE' and o.expiresAt<=:now", UUID.class);
    }

    public jakarta.persistence.TypedQuery<com.credflow.entity.WorkflowRow> pendingEligibilityQuery() {
        return em.createQuery("select w from CfWorkflow w where w.status='ELIGIBILITY_CHECKING' and w.eligibilityOperationId is not null", com.credflow.entity.WorkflowRow.class);
    }

    public jakarta.persistence.TypedQuery<com.credflow.entity.WorkflowRow> openFinancialPermissionsQuery() {
        return em.createQuery("select w from CfWorkflow w where w.status not in ('CHECK_FAILED','NOT_ELIGIBLE','OFFER_ACCEPTED','OFFER_DECLINED','OFFER_EXPIRED','REJECTED','CANCELLED','DRAFT') order by w.updatedAt", com.credflow.entity.WorkflowRow.class);
    }

    private String submissionFilter(String lender, UUID marketplace) {
        List<String> predicates = new ArrayList<>();
        if (lender != null)
            predicates.add("s.lenderId=:l");
        if (marketplace != null)
            predicates.add("exists (select w.id from CfWorkflow w where w.id=s.applicationId and w.marketplaceId=:m)");
        return predicates.isEmpty() ? "" : " where " + String.join(" and ", predicates);
    }
}
