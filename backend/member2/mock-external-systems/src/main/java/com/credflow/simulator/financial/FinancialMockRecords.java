package com.credflow.simulator.financial;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialMockRecords extends JpaRepository<FinancialMockRecord, String> {
}
