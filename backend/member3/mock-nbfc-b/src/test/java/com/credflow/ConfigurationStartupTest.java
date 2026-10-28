package com.credflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Checks the actual application graph and imported configuration offline. This
 * does not validate Oracle tables, JWT signatures or Kafka connectivity.
 */
@SpringBootTest(classes = MockNbfcBApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"AUTH_ISSUER=https://offline.test/auth", "JWT_JWKS_URI=https://offline.test/keys", "JWT_ROLE_MODE=KEYCLOAK",
		"OAUTH_TOKEN_URL=https://offline.test/token", "NBFC_B_DB_PASSWORD=offline-nbfc-b-only",
		"spring.jpa.hibernate.ddl-auto=none", "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
		"spring.datasource.hikari.initialization-fail-timeout=-1", "spring.kafka.listener.auto-startup=false",
		"spring.kafka.admin.auto-create=false", "spring.kafka.admin.fail-fast=false",
		"management.health.db.enabled=false", "FRONTEND_ORIGINS=http://localhost:5173" })
@AutoConfigureMockMvc
@Import(ConfigurationStartupTest.OfflineScheduling.class)
class ConfigurationStartupTest {

	@Autowired
	Environment environment;

	@Autowired
	MockMvc mvc;

	@Test
	void importedOracleConfigurationAndServiceIdentityResolve() {
		assertEquals("oracle.jdbc.OracleDriver",
				environment.getRequiredProperty("spring.datasource.driver-class-name"));
		assertEquals("org.hibernate.dialect.OracleDialect",
				environment.getRequiredProperty("spring.jpa.database-platform"));
		assertEquals("mock-nbfc-b", environment.getRequiredProperty("credflow.service-name"));
		assertEquals("mock-nbfc-b", environment.getRequiredProperty("credflow.security.audience"));
		assertEquals("false", environment.getRequiredProperty("credflow.events.enabled"));
		assertFalse(environment.getRequiredProperty("spring.datasource.password").contains("${"));
		assertFalse(environment.getRequiredProperty("credflow.oauth.clients.system.client-secret").contains("${"));
		assertTrue(environment.getRequiredProperty("credflow.oauth.clients.system.client-secret").isBlank());
		assertTrue(environment.getRequiredProperty("credflow.oauth.token-url").equals("https://offline.test/token"));
	}

	@Test
	void businessEndpointRequiresAuthentication() throws Exception {
		mvc.perform(get("/mock/lenders/nbfc-b/applications/by-reference/33333333-3333-4333-8333-333333333333"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
				.andExpect(jsonPath("$.correlationId").isNotEmpty()).andExpect(header().exists("X-Correlation-ID"));
	}

	@Test
	void configuredFrontendCanPreflightAuthenticatedCommands() throws Exception {
		mvc.perform(options("/mock/lenders/nbfc-b/applications/by-reference/33333333-3333-4333-8333-333333333333")
				.header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "Authorization,Content-Type,Idempotency-Key"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
	}

	@Autowired
	org.springframework.context.ApplicationContext context;

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	org.springframework.security.oauth2.jwt.JwtDecoder testDecoder;

	@Test
	void onlyThisBanksDatasourceAndEntitiesAreLoaded() {
		var sources = context.getBeansOfType(javax.sql.DataSource.class);
		assertEquals(1, sources.size());
		var source = (com.zaxxer.hikari.HikariDataSource) sources.values().iterator().next();
		assertEquals("CF_SIM_NBFC_B", source.getUsername());
		assertEquals("nbfc-b", environment.getRequiredProperty("credflow.simulator.bank-code"));
		assertNull(environment.getProperty("credflow.simulator.bank-a.password"));
		var entities = context.getBean(jakarta.persistence.EntityManagerFactory.class).getMetamodel().getEntities();
		assertEquals(java.util.Set.of("SimCase", "SimCallback"), entities.stream()
				.map(jakarta.persistence.metamodel.EntityType::getName).collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void authenticatedRequestForOtherBankIsRejectedBeforeDatabaseAccess() throws Exception {
		var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("offline-test").header("alg", "RS256")
				.subject("integration-service").claim("client_id", "integration-service")
				.claim("scope", "lender.lookup").build();
		org.mockito.Mockito.when(testDecoder.decode("offline-test")).thenReturn(jwt);
		mvc.perform(get("/mock/lenders/bank-a/applications/by-reference/33333333-3333-4333-8333-333333333333")
				.header("Authorization", "Bearer offline-test")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("LENDER_NOT_FOUND"));
	}

	@Autowired
	org.springframework.context.ApplicationContext layeredContext;

	@Autowired
	@org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
	org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping layeredMappings;

	@Test
	void apiInterfaceMethodsRemainRegisteredAfterMovingPackages() {
		Class<?> api = com.credflow.api.NbfcBApi.class;
		Class<?> controller = com.credflow.controller.NbfcBController.class;
		assertTrue(api.isAssignableFrom(controller));
		var expected = java.util.Arrays.stream(api.getDeclaredMethods()).map(java.lang.reflect.Method::getName)
				.collect(java.util.stream.Collectors.toSet());
		var actual = layeredMappings.getHandlerMethods().values().stream()
				.filter(handler -> controller.isAssignableFrom(handler.getBeanType()))
				.map(handler -> handler.getMethod().getName()).collect(java.util.stream.Collectors.toSet());
		assertFalse(expected.isEmpty());
		assertEquals(expected, actual);
		assertEquals("com.credflow.controller", controller.getPackageName());
		assertEquals("com.credflow.service", com.credflow.service.NbfcBSimulatorService.class.getPackageName());
	}

	@Test
	void entityScanLoadsOnlyThisApplicationsMappedTables() {
		var entities = layeredContext.getBean(jakarta.persistence.EntityManagerFactory.class).getMetamodel()
				.getEntities();
		assertEquals(java.util.Set.of("SimCallback", "SimCase"), entities.stream()
				.map(jakarta.persistence.metamodel.EntityType::getName).collect(java.util.stream.Collectors.toSet()));
		for (var entity : entities) {
			for (var field : entity.getJavaType().getDeclaredFields()) {
				if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
					assertTrue(java.lang.reflect.Modifier.isPrivate(field.getModifiers()),
							entity.getName() + "." + field.getName() + " must be private");
				}
			}
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class OfflineScheduling {

		@Bean
		static BeanFactoryPostProcessor disableScheduledDatabaseWork() {
			return beanFactory -> {
				if (beanFactory instanceof BeanDefinitionRegistry registry && registry
						.containsBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
					registry.removeBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
				}
			};
		}
	}
}
