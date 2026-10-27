package com.credflow.eligibility;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.*;
import com.credflow.platform.persistence.*;
@Configuration
public class PersistenceConfiguration {
 @Bean DatabaseAccess databaseAccess(EntityManager entityManager){return new JpaDatabaseAccess(entityManager);}
}
