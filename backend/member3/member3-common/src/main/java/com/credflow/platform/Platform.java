package com.credflow.platform;

import com.credflow.platform.entity.DeliveryRow;
import com.fasterxml.jackson.databind.*;
import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class Platform {

	public record Actor(String type, String subject, String organisationId) {
	}

	public record Event(UUID eventId, String eventType, String schemaVersion, Instant occurredAt, String producer,
			UUID applicationId, long aggregateVersion, String correlationId, UUID causationId, JsonNode data,
			Actor actor, Actor originActor) {
	}

	public interface Handler {

		void onEvent(Event event);
	}

	public static class Pending extends RuntimeException {

		public Pending(String code) {
			super(code);
		}
	}

	public record Job(String target, String path, JsonNode payload, String key, Actor origin, String correlationId) {
	}

	/**
	 * Receives durable HTTP-job changes in the same transaction as the delivery
	 * row.
	 */
	public interface JobObserver {

		default boolean canRunJob(UUID applicationId, UUID jobId, Job job) {
			return true;
		}

		default void onJobResponse(UUID applicationId, UUID jobId, Job job, JsonNode response) {
		}

		void onJobState(UUID applicationId, UUID jobId, Job job, String state, String error);
	}

	private record Token(String value, Instant expires) {
	}

	@PersistenceContext
	EntityManager em;

	final Environment env;

	final ObjectMapper mapper;

	final ThreadLocal<Event> context = new ThreadLocal<>();

	private final TransactionTemplate transaction;

	private final RestClient http;

	private final Map<String, Token> tokens = new ConcurrentHashMap<>();

	public Platform(Environment env, ObjectMapper mapper, PlatformTransactionManager manager) {
		this.env = env;
		this.mapper = mapper;
		transaction = new TransactionTemplate(manager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transaction.setTimeout(15);
		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(2000);
		factory.setReadTimeout(4000);
		http = RestClient.builder().requestFactory(factory).build();
	}

	// Access through methods so Spring proxies delegate to the initialized target.
	public Environment environment() {
		return env;
	}

	public void setEventContext(Event event) {
		context.set(event);
	}

	public void clearEventContext() {
		context.remove();
	}

	public Event currentEvent() {
		return context.get();
	}

	public <T> T tx(Supplier<T> action) {
		return transaction.execute(status -> action.get());
	}

	public void jobContext(Job job, Runnable action) {
		String previousCorrelation = MDC.get("correlationId");
		Event previousEvent = context.get();
		Event cause = cause(job.payload());
		MDC.put("correlationId", job.correlationId());
		if (cause == null)
			context.remove();
		else
			context.set(cause);
		try {
			action.run();
		} finally {
			if (previousCorrelation == null)
				MDC.remove("correlationId");
			else
				MDC.put("correlationId", previousCorrelation);
			if (previousEvent == null)
				context.remove();
			else
				context.set(previousEvent);
		}
	}

	public JsonNode json(Object value) {
		return mapper.valueToTree(value);
	}

	public <T> T read(JsonNode value, Class<T> type) {
		try {
			return mapper.treeToValue(value, type);
		} catch (Exception exception) {
			throw new IllegalArgumentException("INVALID_PAYLOAD", exception);
		}
	}

	public <T> T read(String value, Class<T> type) {
		return read(readTree(value), type);
	}

	public String write(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalArgumentException("INVALID_PAYLOAD", exception);
		}
	}

	public String hash(Object value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(write(canonical(json(value))).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private JsonNode canonical(JsonNode node) {
		if (node.isObject()) {
			var result = mapper.createObjectNode();
			var names = new TreeSet<String>();
			node.fieldNames().forEachRemaining(names::add);
			names.forEach(name -> result.set(name, canonical(node.get(name))));
			return result;
		}
		if (node.isArray()) {
			var result = mapper.createArrayNode();
			node.forEach(value -> result.add(canonical(value)));
			return result;
		}
		return node;
	}

	public String client(Jwt jwt) {
		String azp = jwt.getClaimAsString("azp");
		return azp == null ? jwt.getClaimAsString("client_id") : azp;
	}

	public boolean hasRole(Jwt jwt, String role) {
		Object raw;
		if ("KEYCLOAK".equals(env.getProperty("credflow.security.role-mode", "FLAT"))) {
			Object access = jwt.getClaim("resource_access");
			if (!(access instanceof Map<?, ?> clients))
				return false;
			Object client = clients.get(env.getProperty("credflow.security.roles-client", "credflow-ui"));
			if (!(client instanceof Map<?, ?> clientAccess))
				return false;
			raw = clientAccess.get("roles");
		} else {
			String claim = env.getProperty("credflow.security.role-claim", "roles");
			raw = jwt.getClaims().get(claim);
			if (raw == null && "roles".equals(claim))
				raw = jwt.getClaims().get("role");
		}
		var roles = raw instanceof Collection<?> values
				? values.stream().filter(String.class::isInstance).map(String.class::cast).toList()
				: raw instanceof String value ? Arrays.asList(value.split("[ ,]+")) : List.<String>of();
		return roles.stream().anyMatch(
				value -> role.equals(value) || ("MARKETPLACE_OPERATIONS".equals(role) && "MARKETPLACE".equals(value)));
	}

	/**
	 * Identity claims are signed Auth assertions, never marketplace request fields.
	 */
	public String sellerId(Jwt jwt) {
		return identity(jwt, "sellerId", "seller_id");
	}

	public String lenderId(Jwt jwt) {
		return identity(jwt, "lenderId", "lender_id");
	}

	public String marketplaceId(Jwt jwt) {
		return identity(jwt, "marketplaceId", "marketplace_id");
	}

	private String identity(Jwt jwt, String primary, String legacy) {
		Object value = jwt.getClaims().containsKey(primary) ? jwt.getClaims().get(primary)
				: jwt.getClaims().get(legacy);
		return value instanceof String text && !text.isBlank() ? text : null;
	}

	public void requireScope(Jwt jwt, String scope) {
		Object value = jwt.getClaims().getOrDefault("scope", jwt.getClaims().get("scp"));
		var scopes = value instanceof Collection<?> collection ? collection.stream().map(Object::toString).toList()
				: Arrays.asList(Objects.toString(value, "").split(" +"));
		check(scopes.contains(scope), 403, "INSUFFICIENT_SCOPE");
	}

	public void machine(Jwt jwt, String scope, String expectedClient) {
		requireScope(jwt, scope);
		check(expectedClient != null && expectedClient.equals(client(jwt)), 403, "WRONG_SYSTEM_CLIENT");
	}

	public void owner(Jwt jwt, String sellerSubject, String lenderId, boolean readOnly) {
		owner(jwt, sellerSubject, null, lenderId, null, readOnly);
	}

	public void owner(Jwt jwt, String sellerSubject, String lenderId, UUID marketplaceId, boolean readOnly) {
		owner(jwt, sellerSubject, null, lenderId, marketplaceId, readOnly);
	}

	public void owner(Jwt jwt, String sellerSubject, String trustedSellerId, String lenderId, UUID marketplaceId,
			boolean readOnly) {
		boolean seller = hasRole(jwt, "SELLER") && (trustedSellerId != null ? trustedSellerId.equals(sellerId(jwt))
				: sellerSubject != null && sellerSubject.equals(jwt.getSubject()));
		boolean lender = lenderId != null && lenderId.equals(lenderId(jwt)) && hasRole(jwt, "LENDER_OFFICER");
		boolean administrator = readOnly && hasRole(jwt, "ADMIN");
		boolean operations = readOnly && marketplaceId != null && marketplaceId.toString().equals(marketplaceId(jwt))
				&& hasRole(jwt, "MARKETPLACE_OPERATIONS");
		check(seller || lender || administrator || operations, 404, "RESOURCE_NOT_FOUND");
	}

	public Actor actor(Jwt jwt) {
		return new Actor("HUMAN", jwt.getSubject(), lenderId(jwt) != null ? lenderId(jwt) : marketplaceId(jwt));
	}

	public Actor systemActor() {
		return new Actor("SYSTEM_CLIENT", env.getProperty("credflow.oauth.clients.system.client-id",
				env.getRequiredProperty("credflow.service-name")), null);
	}

	public static void check(boolean valid, int status, String code) {
		if (!valid) {
			throw new ResponseStatusException(HttpStatusCode.valueOf(status), code);
		}
	}

	public JsonNode call(String registration, HttpMethod method, String url, Object body, String key,
			String correlation) {
		var request = http.method(method).uri(url).headers(headers -> {
			headers.setBearerAuth(token(registration));
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.set("X-Correlation-ID", correlation == null ? UUID.randomUUID().toString() : correlation);
			if (key != null) {
				headers.set("Idempotency-Key", key);
			}
		});
		if (body != null) {
			request.body(body);
		}
		return request.retrieve().body(JsonNode.class);
	}

	private synchronized String token(String registration) {
		Token cached = tokens.get(registration);
		if (cached != null && cached.expires().isAfter(Instant.now().plusSeconds(15))) {
			return cached.value();
		}
		String prefix = "credflow.oauth.clients." + registration;
		String tokenUrl = env.getProperty("credflow.oauth.token-url", "");
		check(!tokenUrl.isBlank() && !tokenUrl.contains("REPLACE"), 503, "SERVICE_AUTHENTICATION_NOT_CONFIGURED");
		String clientId = env.getProperty(prefix + ".client-id", "");
		String clientSecret = env.getProperty(prefix + ".client-secret", "");
		check(!clientId.isBlank() && !clientSecret.isBlank() && !clientId.contains("REPLACE")
				&& !clientSecret.contains("REPLACE"), 503, "SERVICE_AUTHENTICATION_NOT_CONFIGURED");
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "client_credentials");
		JsonNode response = http.post().uri(tokenUrl).headers(headers -> headers.setBasicAuth(clientId, clientSecret))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
		check(response != null && response.hasNonNull("access_token"), 502, "TOKEN_RESPONSE_INVALID");
		String value = response.get("access_token").asText();
		tokens.put(registration, new Token(value, Instant.now().plusSeconds(response.path("expires_in").asLong(60))));
		return value;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Event emit(String topic, UUID applicationId, long version, Object data, Actor actor, Actor origin,
			Event cause) {
		String correlation = cause == null ? Objects.toString(MDC.get("correlationId"), UUID.randomUUID().toString())
				: cause.correlationId();
		var event = new Event(UUID.randomUUID(), topic, "1.0", Instant.now(),
				env.getRequiredProperty("credflow.service-name"), applicationId, Math.max(1, version), correlation,
				cause == null ? null : cause.eventId(), json(data), actor, origin);
		add("OUTBOX", event.eventId(), applicationId, topic, write(event));
		return event;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void job(UUID id, String target, String path, Object payload, String key, UUID applicationId, Actor origin) {
		check(path != null && (path.startsWith("/api/v1/") || path.startsWith("/internal/v1/")) && !path.contains("..")
				&& !path.contains("://"), 400, "INVALID_JOB_PATH");
		Event cause = context.get();
		String correlation = cause == null ? Objects.toString(MDC.get("correlationId"), id.toString())
				: cause.correlationId();
		JsonNode body = json(payload);
		if (body.isObject()) {
			var object = (com.fasterxml.jackson.databind.node.ObjectNode) body;
			object.put("correlationId", correlation);
			if (cause != null) {
				object.put("causationId", cause.eventId().toString());
			}
		}
		add("JOB", id, applicationId, target, write(new Job(target, path, body, key, origin, correlation)));
	}

	public Event cause(JsonNode request) {
		if (!request.hasNonNull("causationId")) {
			return null;
		}
		return new Event(UUID.fromString(request.path("causationId").asText()), "upstream", "1.0", Instant.now(),
				"upstream", null, 1, request.path("correlationId").asText(UUID.randomUUID().toString()), null,
				json(null), systemActor(), null);
	}

	void add(String kind, UUID id, UUID applicationId, String target, String payload) {
		String key = kind + ":" + id;
		DeliveryRow existing = em.find(DeliveryRow.class, key);
		if (existing != null) {
			check(existing.getHash().equals(hash(readTree(payload))), 409, "IDEMPOTENCY_CONFLICT");
			return;
		}
		var row = new DeliveryRow();
		row.setId(key);
		row.setKind(kind);
		row.setApplicationId(applicationId);
		row.setTarget(target);
		row.setPayload(payload);
		row.setHash(hash(readTree(payload)));
		row.setPosition(((Number) em.createNativeQuery("select CF_M3_DELIVERY_SEQ.nextval from dual").getSingleResult())
				.longValue());
		row.setStatus("PENDING");
		row.setNextAttempt(Instant.now());
		row.setCreatedAt(Instant.now());
		em.persist(row);
	}

	JsonNode readTree(String value) {
		try {
			return mapper.readTree(value);
		} catch (Exception exception) {
			throw new IllegalArgumentException("INVALID_JSON", exception);
		}
	}
}
