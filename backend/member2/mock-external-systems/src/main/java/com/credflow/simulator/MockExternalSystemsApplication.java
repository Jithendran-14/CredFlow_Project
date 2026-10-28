package com.credflow.simulator;

import com.credflow.platform.PlatformConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(PlatformConfiguration.class)
public class MockExternalSystemsApplication {
	public static void main(String[] args) {
		SpringApplication.run(MockExternalSystemsApplication.class, args);
	}
}
