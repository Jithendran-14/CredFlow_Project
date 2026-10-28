package com.credflow.auth.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class SellerReferenceService {
	private final JdbcTemplate jdbc;

	public SellerReferenceService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public String next(String market) {
		var rows = jdbc.queryForList(
				"SELECT REFERENCE_PREFIX,NEXT_NUMBER FROM CF_MARKETPLACE_REFERENCES WHERE MARKETPLACE_ID=? FOR UPDATE",
				market);
		if (rows.isEmpty())
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Seller numbering has not been configured for this marketplace.");
		var row = rows.getFirst();
		long n = ((Number) row.get("NEXT_NUMBER")).longValue();
		String prefix = row.get("REFERENCE_PREFIX").toString();
		jdbc.update("UPDATE CF_MARKETPLACE_REFERENCES SET NEXT_NUMBER=NEXT_NUMBER+1 WHERE MARKETPLACE_ID=?", market);
		return prefix + "-" + String.format(java.util.Locale.ROOT, "%04d", n);
	}
}
