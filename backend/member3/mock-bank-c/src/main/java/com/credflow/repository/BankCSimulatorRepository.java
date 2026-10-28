package com.credflow.repository;

import com.credflow.entity.SimCase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Own-schema persistence and query definitions; uses the caller's transaction.
 */
@Repository
public class BankCSimulatorRepository {

	private final EntityManager em;

	public BankCSimulatorRepository(EntityManager em) {
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

	public jakarta.persistence.TypedQuery<SimCase> caseByExternalIdQuery() {
		return em.createQuery("select c from SimCase c where " + "c.lenderCode=:b and c.externalId=:x", SimCase.class);
	}

	public jakarta.persistence.TypedQuery<UUID> dueCallbackIdsQuery() {
		return em.createQuery("select c.id from SimCallback c where " + "c.status in ('PENDING','SENDING') "
				+ "and c.lenderCode=:bank " + "and c.nextAttempt<=:now order by c.nextAttempt", UUID.class);
	}

	public jakarta.persistence.TypedQuery<Long> countEarlierCallbacksQuery() {
		return em.createQuery("select count(x) from SimCallback x where " + "x.caseKey=:key and x.sequence<:seq "
				+ "and x.status<>'SENT'", Long.class);
	}
}
