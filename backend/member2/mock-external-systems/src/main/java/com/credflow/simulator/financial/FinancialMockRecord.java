package com.credflow.simulator.financial;

import jakarta.persistence.*;

@Entity
@Table(name = "CF_MOCK_FINANCIAL_RECORD")
public class FinancialMockRecord {
	@Id
	@Column(name = "RECORD_KEY", length = 100)
	String key;
	@Column(name = "INPUT_HASH", nullable = false, length = 64)
	String hash;
	@Lob
	@Column(name = "PAYLOAD", nullable = false)
	String payload;

	protected FinancialMockRecord() {
	}

	FinancialMockRecord(String key, String hash, String payload) {
		this.key = key;
		this.hash = hash;
		this.payload = payload;
	}
}
