package com.credflow.application.correction;

import com.credflow.application.repository.OutboxEventRepository;
import com.credflow.application.entity.OutboxEventEntity;
import com.credflow.application.service.ApplicationService;
import java.util.*;
import java.math.BigDecimal;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.http.HttpStatus.*;

@Service
@Transactional
public class CorrectionService {
	private final JdbcTemplate db;
	private final ObjectMapper json;
	private final OutboxEventRepository outbox;
	private final ApplicationService applications;

	public CorrectionService(JdbcTemplate db, ObjectMapper json, OutboxEventRepository outbox,
			ApplicationService applications) {
		this.db = db;
		this.json = json;
		this.outbox = outbox;
		this.applications = applications;
	}

	public Object submit(UUID app, CorrectionInput input, String key, Jwt jwt) {
		if (jwt == null || jwt.getClaimAsStringList("roles") == null
				|| !jwt.getClaimAsStringList("roles").contains("SELLER") || jwt.getClaimAsString("client_id") != null)
			throw new ResponseStatusException(FORBIDDEN, "Seller access required.");
		try {
			UUID.fromString(key);
		} catch (Exception ex) {
			throw new ResponseStatusException(BAD_REQUEST, "A valid retry key is required.");
		}
		validate(input);
		var rows = db.queryForList(
				"SELECT * FROM CF_APPLICATIONS WHERE PUBLIC_APPLICATION_ID=? AND SELLER_ID=? FOR UPDATE",
				app.toString(), jwt.getClaimAsString("sellerId"));
		if (rows.isEmpty())
			throw new ResponseStatusException(NOT_FOUND, "Application not found.");
		var old = rows.getFirst();
		String payload = json.writeValueAsString(input), hash = hash(payload), retry = jwt.getSubject() + ":" + key;
		var prior = db.queryForList(
				"SELECT OPERATION_ID,REQUEST_HASH FROM CF_APPLICATION_CORRECTIONS WHERE APPLICATION_ID=? AND RETRY_KEY=?",
				app.toString(), retry);
		if (!prior.isEmpty()) {
			var r = prior.getFirst();
			if (!hash.equals(r.get("REQUEST_HASH")))
				throw new ResponseStatusException(CONFLICT, "This retry key was used with different corrections.");
			return operation(r.get("OPERATION_ID").toString());
		}
		if (!"ACTION_REQUIRED".equals(old.get("STATUS"))
				|| ((Number) old.get("APPLICATION_REVISION")).intValue() != input.expectedApplicationRevision()
				|| ((Number) old.get("VERSION")).intValue() != input.expectedVersion())
			throw new ResponseStatusException(CONFLICT,
					"Only the current ACTION_REQUIRED application can be corrected. Refresh before saving.");
		if (!input.loanPurpose().name().equals(old.get("LOAN_PURPOSE")))
			throw new ResponseStatusException(CONFLICT,
					"A correction cannot change lending purpose. Create a separate request for a different purpose.");
		if (db.queryForObject(
				"SELECT COUNT(*) FROM CF_APPLICATION_CORRECTIONS WHERE APPLICATION_ID=? AND STATUS IN ('PENDING','PROCESSING','RETRY_PENDING')",
				Long.class, app.toString()) > 0)
			throw new ResponseStatusException(CONFLICT,
					"A correction is already being processed. Refresh to see its result.");
		String id = UUID.randomUUID().toString();
		var oldProfile = new LinkedHashMap<String, Object>();
		oldProfile.put("displayName", old.get("DISPLAY_NAME"));
		oldProfile.put("businessName", old.get("BUSINESS_NAME"));
		oldProfile.put("businessStartDate", old.get("BUSINESS_START_DATE").toString().substring(0, 10));
		String previousPhase = db.queryForObject(
				"SELECT PHASE0_JSON FROM CF_APPLICATIONS WHERE PUBLIC_APPLICATION_ID=?", String.class, app.toString());
		oldProfile.put("phase0", previousPhase == null ? null : json.readTree(previousPhase));
		var before = Map.of("requestedAmount",
				new BigDecimal(old.get("REQUESTED_AMOUNT").toString()).setScale(2).toPlainString(), "loanPurpose",
				old.get("LOAN_PURPOSE"), "businessProfile", oldProfile);
		db.update(
				"INSERT INTO CF_APPLICATION_CORRECTIONS(OPERATION_ID,APPLICATION_ID,SELLER_ID,ACTOR_ID,RETRY_KEY,REQUEST_HASH,EXPECTED_REVISION,NEW_REVISION,BEFORE_JSON,AFTER_JSON) VALUES (?,?,?,?,?,?,?,?,?,?)",
				id, app.toString(), old.get("SELLER_ID"), jwt.getSubject(), retry, hash,
				input.expectedApplicationRevision(), input.expectedApplicationRevision() + 1,
				json.writeValueAsString(before), payload);
		return operation(id);
	}

	public static void validate(CorrectionInput i) {
		if (new BigDecimal(i.requestedAmount()).signum() <= 0 || i.businessProfile().displayName().isBlank()
				|| i.businessProfile().businessName().isBlank()
				|| i.businessProfile().businessStartDate().isAfter(LocalDate.now(ZoneOffset.UTC)))
			throw new ResponseStatusException(BAD_REQUEST, "Enter a positive amount and valid business details.");
	}

	@Transactional(readOnly = true)
	public Object list(UUID app, Jwt jwt) {
		applications.getApplication(app.toString(), jwt);
		return Map.of("items", db.query(
				"SELECT OPERATION_ID FROM CF_APPLICATION_CORRECTIONS WHERE APPLICATION_ID=? ORDER BY CREATED_AT DESC",
				(rs, n) -> operation(rs.getString(1)), app.toString()));
	}

	private Map<String, Object> operation(String id) {
		return db.queryForObject(
				"SELECT OPERATION_ID,APPLICATION_ID,STATUS,EXPECTED_REVISION,NEW_REVISION,LAST_ERROR,CREATED_AT,UPDATED_AT FROM CF_APPLICATION_CORRECTIONS WHERE OPERATION_ID=?",
				(rs, n) -> {
					var r = new LinkedHashMap<String, Object>();
					r.put("operationId", rs.getString(1));
					r.put("applicationId", rs.getString(2));
					r.put("status", rs.getString(3));
					r.put("previousRevision", rs.getInt(4));
					r.put("newRevision", rs.getInt(5));
					r.put("errorCode", rs.getString(6));
					r.put("createdAt", rs.getObject(7, OffsetDateTime.class).toString());
					r.put("updatedAt", rs.getObject(8, OffsetDateTime.class).toString());
					return r;
				}, id);
	}

	public List<String> pending() {
		return db.queryForList(
				"SELECT OPERATION_ID FROM CF_APPLICATION_CORRECTIONS WHERE ((STATUS IN ('PENDING','RETRY_PENDING') AND NEXT_ATTEMPT_AT<=SYSTIMESTAMP) OR (STATUS='PROCESSING' AND LEASE_UNTIL<SYSTIMESTAMP)) ORDER BY CREATED_AT FETCH FIRST 8 ROWS ONLY",
				String.class);
	}

	public Map<String, Object> claim(String id, String claim) {
		int n = db.update(
				"UPDATE CF_APPLICATION_CORRECTIONS SET STATUS='PROCESSING',ATTEMPTS=ATTEMPTS+1,CLAIM_TOKEN=?,LEASE_UNTIL=SYSTIMESTAMP+NUMTODSINTERVAL(60,'SECOND'),UPDATED_AT=SYSTIMESTAMP WHERE OPERATION_ID=? AND ((STATUS IN ('PENDING','RETRY_PENDING') AND NEXT_ATTEMPT_AT<=SYSTIMESTAMP) OR (STATUS='PROCESSING' AND LEASE_UNTIL<SYSTIMESTAMP))",
				claim, id);
		if (n == 0)
			return Map.of();
		return db.queryForMap(
				"SELECT OPERATION_ID,APPLICATION_ID,ACTOR_ID,EXPECTED_REVISION,NEW_REVISION FROM CF_APPLICATION_CORRECTIONS WHERE OPERATION_ID=?",
				id);
	}

	public void apply(String id, String claim) {
		var command = db.queryForList(
				"SELECT * FROM CF_APPLICATION_CORRECTIONS WHERE OPERATION_ID=? AND CLAIM_TOKEN=? AND STATUS='PROCESSING' FOR UPDATE",
				id, claim);
		if (command.isEmpty())
			return;
		var c = command.getFirst();
		String app = c.get("APPLICATION_ID").toString();
		var row = db.queryForMap("SELECT * FROM CF_APPLICATIONS WHERE PUBLIC_APPLICATION_ID=? FOR UPDATE", app);
		if (((Number) row.get("APPLICATION_REVISION")).intValue() != ((Number) c.get("EXPECTED_REVISION")).intValue())
			throw new IllegalStateException("Application revision changed");
		String raw = db.queryForObject("SELECT AFTER_JSON FROM CF_APPLICATION_CORRECTIONS WHERE OPERATION_ID=?",
				String.class, id);
		var input = json.readValue(raw, CorrectionInput.class);
		var profile = input.businessProfile();
		if (profile.phase0() == null)
			throw new IllegalArgumentException("Phase 0 details are required.");
		profile.phase0().validate();
		db.update(
				"UPDATE CF_APPLICATIONS SET REQUESTED_AMOUNT=?,LOAN_PURPOSE=?,DISPLAY_NAME=?,BUSINESS_NAME=?,BUSINESS_START_DATE=?,PHASE0_JSON=?,APPLICATION_REVISION=?,VERSION=VERSION+1,UPDATED_AT=SYSTIMESTAMP WHERE PUBLIC_APPLICATION_ID=?",
				new BigDecimal(input.requestedAmount()), input.loanPurpose().name(), profile.displayName().trim(),
				profile.businessName().trim(), java.sql.Date.valueOf(profile.businessStartDate()),
				json.writeValueAsString(profile.phase0()), c.get("NEW_REVISION"), app);
		var actor = Map.of("type", "HUMAN", "subject", c.get("ACTOR_ID"), "organisationId", row.get("MARKETPLACE_ID"));
		var data = new LinkedHashMap<String, Object>();
		data.put("operationId", id);
		data.put("applicationRevision", c.get("NEW_REVISION"));
		data.put("previousRevision", c.get("EXPECTED_REVISION"));
		data.put("sellerId", row.get("SELLER_ID"));
		data.put("marketplaceId", row.get("MARKETPLACE_ID"));
		data.put("marketplaceSellerId", row.get("MARKETPLACE_SELLER_ID"));
		data.put("requestedAmount", input.requestedAmount());
		data.put("currency", "INR");
		data.put("loanPurpose", input.loanPurpose().name());
		data.put("businessProfile", profile);
		String eventId = UUID.randomUUID().toString();
		var event = new LinkedHashMap<String, Object>();
		event.put("eventId", eventId);
		event.put("eventType", "application.revised");
		event.put("schemaVersion", "1.0");
		event.put("occurredAt", Instant.now().toString());
		event.put("producer", "application-service");
		event.put("applicationId", app);
		event.put("aggregateVersion", c.get("NEW_REVISION"));
		event.put("correlationId", id);
		event.put("actor", actor);
		event.put("originActor", actor);
		event.put("data", data);
		outbox.save(OutboxEventEntity.pending(eventId, "APPLICATION", app, "application.revised", 1,
				json.writeValueAsString(event)));
		db.update(
				"UPDATE CF_APPLICATION_CORRECTIONS SET STATUS='APPLIED',CLAIM_TOKEN=NULL,LEASE_UNTIL=NULL,LAST_ERROR=NULL,UPDATED_AT=SYSTIMESTAMP WHERE OPERATION_ID=? AND CLAIM_TOKEN=?",
				id, claim);
	}

	public void failure(String id, String claim, boolean rejected) {
		db.update(
				"UPDATE CF_APPLICATION_CORRECTIONS SET STATUS=?,LAST_ERROR=?,CLAIM_TOKEN=NULL,LEASE_UNTIL=NULL,NEXT_ATTEMPT_AT=SYSTIMESTAMP+NUMTODSINTERVAL(10,'SECOND'),UPDATED_AT=SYSTIMESTAMP WHERE OPERATION_ID=? AND CLAIM_TOKEN=?",
				rejected ? "REJECTED" : "RETRY_PENDING",
				rejected ? "CORRECTION_NOT_ALLOWED" : "CONNECTED_SERVICE_UNAVAILABLE", id, claim);
	}

	private static String hash(String v) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
