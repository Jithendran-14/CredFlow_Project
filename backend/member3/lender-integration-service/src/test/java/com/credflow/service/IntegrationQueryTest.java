package com.credflow.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.credflow.entity.IntegrationCallback;
import com.credflow.entity.IntegrationDispatch;
import com.credflow.entity.IntegrationTask;
import java.util.UUID;
import org.hibernate.cfg.Configuration;
import org.slf4j.LoggerFactory;

class IntegrationQueryTest {

	@org.junit.jupiter.api.Test
	void oracleMappingsAndQueriesParse() {
		((Logger) LoggerFactory.getLogger("org.hibernate")).setLevel(Level.ERROR);
		var config = new Configuration();
		config.setProperty("hibernate.dialect", "org.hibernate.dialect.OracleDialect");
		config.setProperty("hibernate.boot.allow_jdbc_metadata_access", "false");
		config.setProperty("hibernate.hbm2ddl.auto", "none");
		config.addAnnotatedClass(IntegrationDispatch.class);
		config.addAnnotatedClass(IntegrationTask.class);
		config.addAnnotatedClass(IntegrationCallback.class);
		try (var factory = config.buildSessionFactory(); var em = factory.createEntityManager()) {
			em.createQuery("select t.taskId from IntegrationTask t where " + "t.status in ('PENDING','WORKING') "
					+ "and t.nextAttemptAt<=:now " + "and (t.operation='CANCEL' or not exists "
					+ "(select x.taskId from IntegrationTask x where " + "x.submissionId=t.submissionId "
					+ "and x.position<t.position " + "and x.status in ('PENDING','WORKING'))) "
					+ "order by case when t.operation='CANCEL' " + "then 0 else 1 end,t.position", UUID.class);
			em.createQuery("select count(x) from IntegrationTask x where " + "x.submissionId=:s and x.position<:p "
					+ "and x.status in ('PENDING','WORKING')", Long.class);
			em.createQuery(
					"select c.eventId from IntegrationCallback c where "
							+ "c.status='PENDING' and c.nextAttemptAt<=:now " + "order by c.nextAttemptAt,c.receivedAt",
					UUID.class);
			em.createQuery("select c from IntegrationCallback c where " + "c.submissionId=:s and c.lenderSequence=:n",
					IntegrationCallback.class);
			em.createQuery("select t.payloadJson from IntegrationTask t where "
					+ "t.submissionId=:s and t.operation<>'SUBMIT'", String.class);
		}
	}
}
