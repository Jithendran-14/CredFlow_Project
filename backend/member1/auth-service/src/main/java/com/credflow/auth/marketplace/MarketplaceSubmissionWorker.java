package com.credflow.auth.marketplace;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "credflow.marketplace-submission.enabled", havingValue = "true")
public class MarketplaceSubmissionWorker {
	private static final Logger log = LoggerFactory.getLogger(MarketplaceSubmissionWorker.class);
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final String gateway;
	private final MarketplaceWorkerIdentity identity;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	public MarketplaceSubmissionWorker(JdbcTemplate jdbc, ObjectMapper mapper, MarketplaceWorkerIdentity identity,
			@Value("${credflow.marketplace-submission.gateway-url:http://127.0.0.1:5174/api/proxy/application/api/v1/applications}") String gateway) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.identity = identity;
		this.gateway = gateway;
	}

	@Scheduled(fixedDelayString = "${credflow.marketplace-submission.poll-ms:2000}")
	public void tick() {
		try {
			var ids = jdbc.queryForList(
					"SELECT REQUEST_ID FROM CF_MARKETPLACE_LOAN_REQUESTS WHERE ((STATUS IN ('QUEUED','RETRY_PENDING') AND NEXT_ATTEMPT_AT<=SYSTIMESTAMP) OR (STATUS='PROCESSING' AND LEASE_UNTIL<SYSTIMESTAMP)) ORDER BY CREATED_AT FETCH FIRST 8 ROWS ONLY",
					String.class);
			for (String id : ids)
				process(id);
		} catch (Exception e) {
			log.warn("Marketplace submission queue temporarily unavailable ({})", e.getClass().getSimpleName());
		}
	}

	private void process(String id) {
		String claim = UUID.randomUUID().toString();
		int n = jdbc.update(
				"UPDATE CF_MARKETPLACE_LOAN_REQUESTS SET STATUS='PROCESSING',CLAIM_TOKEN=?,LEASE_UNTIL=SYSTIMESTAMP+NUMTODSINTERVAL(120,'SECOND'),ATTEMPTS=ATTEMPTS+1,UPDATED_AT=SYSTIMESTAMP WHERE REQUEST_ID=? AND ((STATUS IN ('QUEUED','RETRY_PENDING') AND NEXT_ATTEMPT_AT<=SYSTIMESTAMP) OR (STATUS='PROCESSING' AND LEASE_UNTIL<SYSTIMESTAMP))",
				claim, id);
		if (n == 0)
			return;
		String failure = "MARKETPLACE_TEMPORARILY_UNAVAILABLE";
		boolean permanent = false;
		try {
			var row = jdbc.queryForMap(
					"SELECT MARKETPLACE_ID,ATTEMPTS FROM CF_MARKETPLACE_LOAN_REQUESTS WHERE REQUEST_ID=?", id);
			String market = row.get("MARKETPLACE_ID").toString();
			String token = identity.token(market);
			String payload = jdbc.queryForObject(
					"SELECT PAYLOAD_JSON FROM CF_MARKETPLACE_LOAN_REQUESTS WHERE REQUEST_ID=?", String.class, id);
			var result = http.send(
					HttpRequest.newBuilder(URI.create(gateway)).timeout(Duration.ofSeconds(15))
							.header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
							.header("Idempotency-Key", id).header("X-Correlation-ID", id)
							.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
					HttpResponse.BodyHandlers.ofString());
			if (result.statusCode() != 200 && result.statusCode() != 201) {
				failure = "APPLICATION_SUBMISSION_RETRY_PENDING";
				permanent = result.statusCode() == 400 || result.statusCode() == 409 || result.statusCode() == 422;
				throw new IllegalStateException();
			}
			String application = mapper.readTree(result.body()).path("applicationId").asText();
			UUID.fromString(application);
			jdbc.update(
					"UPDATE CF_MARKETPLACE_LOAN_REQUESTS SET STATUS='SUBMITTED',APPLICATION_ID=?,LAST_ERROR=NULL,CLAIM_TOKEN=NULL,LEASE_UNTIL=NULL,UPDATED_AT=SYSTIMESTAMP WHERE REQUEST_ID=? AND CLAIM_TOKEN=?",
					application, id, claim);
			return;
		} catch (Exception e) {
			Integer attempts = jdbc.queryForObject(
					"SELECT ATTEMPTS FROM CF_MARKETPLACE_LOAN_REQUESTS WHERE REQUEST_ID=?", Integer.class, id);
			long delay = Math.min(60, 2L << Math.min(attempts, 5));
			jdbc.update(
					"UPDATE CF_MARKETPLACE_LOAN_REQUESTS SET STATUS=?,LAST_ERROR=?,NEXT_ATTEMPT_AT=SYSTIMESTAMP+NUMTODSINTERVAL(?,'SECOND'),CLAIM_TOKEN=NULL,LEASE_UNTIL=NULL,UPDATED_AT=SYSTIMESTAMP WHERE REQUEST_ID=? AND CLAIM_TOKEN=?",
					permanent || attempts >= 20 ? "ACTION_REQUIRED" : "RETRY_PENDING", failure, delay, id, claim);
		}
	}
}
