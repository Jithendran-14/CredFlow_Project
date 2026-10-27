package com.credflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EntityScan(basePackages = "com.credflow.entity")
public class MockBankCApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockBankCApplication.class, args);
    }
}
