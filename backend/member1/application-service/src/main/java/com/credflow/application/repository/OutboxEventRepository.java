package com.credflow.application.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.credflow.application.entity.OutboxEventEntity;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

	@Query("""
			select e.outboxEventId
			from OutboxEventEntity e
			where (
			    e.eventStatus in ('PENDING', 'FAILED')
			    and (
			        e.nextAttemptAt is null
			        or e.nextAttemptAt <= :now
			    )
			)
			or (
			    e.eventStatus = 'PROCESSING'
			    and e.leaseExpiresAt <= :now
			)
			order by e.createdAt, e.outboxEventId
			""")
	List<Long> findClaimCandidates(@Param("now") OffsetDateTime now, Pageable pageable);

	@Modifying
	@Query("""
			update OutboxEventEntity e
			set e.eventStatus = 'PROCESSING',
			    e.claimToken = :claimToken,
			    e.claimedAt = :now,
			    e.leaseExpiresAt = :leaseExpiresAt,
			    e.nextAttemptAt = null
			where e.outboxEventId = :id
			and (
			    (
			        e.eventStatus in ('PENDING', 'FAILED')
			        and (
			            e.nextAttemptAt is null
			            or e.nextAttemptAt <= :now
			        )
			    )
			    or (
			        e.eventStatus = 'PROCESSING'
			        and e.leaseExpiresAt <= :now
			    )
			)
			""")
	int tryClaim(@Param("id") Long id, @Param("claimToken") String claimToken, @Param("now") OffsetDateTime now,
			@Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt);

	@Modifying
	@Query("""
			update OutboxEventEntity e
			set e.eventStatus = 'PUBLISHED',
			    e.publishedAt = :now,
			    e.nextAttemptAt = null,
			    e.claimToken = null,
			    e.claimedAt = null,
			    e.leaseExpiresAt = null
			where e.outboxEventId = :id
			  and e.eventStatus = 'PROCESSING'
			  and e.claimToken = :claimToken
			""")
	int markPublishedIfOwned(@Param("id") Long id, @Param("claimToken") String claimToken,
			@Param("now") OffsetDateTime now);

	@Modifying
	@Query("""
			update OutboxEventEntity e
			set e.eventStatus = 'FAILED',
			    e.retryCount = e.retryCount + 1,
			    e.nextAttemptAt = :nextAttemptAt,
			    e.claimToken = null,
			    e.claimedAt = null,
			    e.leaseExpiresAt = null
			where e.outboxEventId = :id
			  and e.eventStatus = 'PROCESSING'
			  and e.claimToken = :claimToken
			""")
	int markFailedIfOwned(@Param("id") Long id, @Param("claimToken") String claimToken,
			@Param("nextAttemptAt") OffsetDateTime nextAttemptAt);
}