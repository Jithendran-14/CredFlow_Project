package com.credflow.platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
class PlatformProxyRegressionTest {
 @Test void deliveryDependenciesAndEventContextWorkThroughSpringProxy(){
  var env=new MockEnvironment().withProperty("credflow.delivery.max-attempts","20");
  var target=new Platform(env,new ObjectMapper(),mock(PlatformTransactionManager.class));
  var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
  Platform proxy=(Platform)factory.getProxy();
  assertEquals(20,proxy.environment().getProperty("credflow.delivery.max-attempts",Integer.class));
  var actor=new Platform.Actor("SYSTEM","integration-test",null);
  var event=new Platform.Event(UUID.randomUUID(),"application.submitted","1.0",Instant.now(),"application-service",UUID.randomUUID(),1,"trace",null,new ObjectMapper().createObjectNode(),actor,null);
  proxy.setEventContext(event);assertSame(event,target.currentEvent());proxy.clearEventContext();assertNull(target.currentEvent());
 }
}
