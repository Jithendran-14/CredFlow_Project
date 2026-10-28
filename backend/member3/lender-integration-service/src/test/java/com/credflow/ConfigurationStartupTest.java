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
@SpringBootTest(classes = LenderIntegrationServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
		"AUTH_ISSUER=https://offline.test/auth", "JWT_JWKS_URI=https://offline.test/keys", "JWT_ROLE_MODE=KEYCLOAK",
		"OAUTH_TOKEN_URL=https://offline.test/token", "INTEGRATION_DB_PASSWORD=offline-test-only",
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
		assertEquals("lender-integration-service", environment.getRequiredProperty("credflow.service-name"));
		assertEquals("lender-integration-service", environment.getRequiredProperty("credflow.security.audience"));
		assertFalse(environment.getRequiredProperty("spring.datasource.password").contains("${"));
		assertFalse(environment.getRequiredProperty("credflow.oauth.clients.system.client-secret").contains("${"));
		assertTrue(environment.getRequiredProperty("credflow.oauth.clients.system.client-secret").isBlank());
		assertTrue(environment.getRequiredProperty("credflow.oauth.token-url").equals("https://offline.test/token"));
	}

	@Test
	void businessEndpointRequiresAuthentication() throws Exception {
		mvc.perform(get("/api/v1/internal/submissions/33333333-3333-4333-8333-333333333333"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
				.andExpect(jsonPath("$.correlationId").isNotEmpty()).andExpect(header().exists("X-Correlation-ID"));
	}

	@Test
	void configuredFrontendCanPreflightAuthenticatedCommands() throws Exception {
		mvc.perform(options("/api/v1/internal/submissions/33333333-3333-4333-8333-333333333333")
				.header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "Authorization,Content-Type,Idempotency-Key"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
	}

	@Autowired
	org.springframework.context.ApplicationContext layeredContext;

	@Autowired
	@org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
	org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping layeredMappings;

	@Test
	void apiInterfaceMethodsRemainRegisteredAfterMovingPackages() {
		Class<?> api = com.credflow.api.LenderIntegrationApi.class;
		Class<?> controller = com.credflow.controller.LenderIntegrationController.class;
		assertTrue(api.isAssignableFrom(controller));
		var expected = java.util.Arrays.stream(api.getDeclaredMethods()).map(java.lang.reflect.Method::getName)
				.collect(java.util.stream.Collectors.toSet());
		var actual = layeredMappings.getHandlerMethods().values().stream()
				.filter(handler -> controller.isAssignableFrom(handler.getBeanType()))
				.map(handler -> handler.getMethod().getName()).collect(java.util.stream.Collectors.toSet());
		assertFalse(expected.isEmpty());
		assertEquals(expected, actual);
		assertEquals("com.credflow.controller", controller.getPackageName());
		assertEquals("com.credflow.service", com.credflow.service.LenderIntegrationService.class.getPackageName());
	}

	@Test
	void entityScanLoadsOnlyThisApplicationsMappedTables() {
		var entities = layeredContext.getBean(jakarta.persistence.EntityManagerFactory.class).getMetamodel()
				.getEntities();
		assertEquals(java.util.Set.of("DeliveryRow", "IntegrationCallback", "IntegrationDispatch", "IntegrationTask"),
				entities.stream().map(jakarta.persistence.metamodel.EntityType::getName)
						.collect(java.util.stream.Collectors.toSet()));
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
