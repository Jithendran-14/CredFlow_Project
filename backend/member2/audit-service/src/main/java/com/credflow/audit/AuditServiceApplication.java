package com.credflow.audit;

import com.credflow.platform.PlatformConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(PlatformConfiguration.class)
public class AuditServiceApplication {
	public static void main(String[] args) {
		SpringApplication.run(AuditServiceApplication.class, args);
	}
}
