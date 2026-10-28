package com.credflow.audit;

import com.credflow.platform.persistence.DatabaseAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.sql.*;
import java.time.LocalDate;

final class JdbcTestAccess implements DatabaseAccess {
	private final JdbcTemplate jdbc;

	JdbcTestAccess(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public int update(String sql, Object... args) {
		return jdbc.update(sql, args);
	}

	public <T> List<T> query(String sql, Mapper<T> mapper, Object... args) {
		return jdbc.query(sql, (rs, n) -> mapper.map(new Row() {
			public Object value(Object k) throws SQLException {
				return k instanceof Integer i ? rs.getObject(i) : rs.getObject(k.toString());
			}

			public <V> V getObject(Object k, Class<V> type) throws SQLException {
				return k instanceof Integer i ? rs.getObject(i, type) : rs.getObject(k.toString(), type);
			}
		}, n), args);
	}

	public List<Map<String, Object>> queryForList(String sql, Object... args) {
		return jdbc.queryForList(sql, args);
	}

	public String buildSnapshot(String app, int rev, LocalDate date, String batch) {
		return jdbc.execute((Connection conn) -> {
			try (var call = conn.prepareCall("{call CF_FINANCIAL_SUMMARY_PKG.BUILD_SNAPSHOT(?,?,?,?,?)}")) {
				call.setString(1, app);
				call.setInt(2, rev);
				call.setDate(3, java.sql.Date.valueOf(date));
				call.setString(4, batch);
				call.registerOutParameter(5, Types.VARCHAR);
				call.execute();
				return call.getString(5);
			}
		});
	}
}
