package com.credflow.platform.persistence;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

/**
 * Small boundary for Oracle-native financial queries and atomic event delivery.
 */
public interface DatabaseAccess {
	@FunctionalInterface
	interface Mapper<T> {
		T map(Row row, int index) throws SQLException;
	}

	interface Row {
		Object value(Object key) throws SQLException;

		default String getString(Object k) throws SQLException {
			Object v = value(k);
			if (v instanceof Clob c)
				return c.getSubString(1, Math.toIntExact(c.length()));
			return v == null ? null : v.toString();
		}

		default int getInt(Object k) throws SQLException {
			Object v = value(k);
			return v == null ? 0 : ((Number) v).intValue();
		}

		default BigDecimal getBigDecimal(Object k) throws SQLException {
			Object v = value(k);
			return v == null ? null : new BigDecimal(v.toString());
		}

		default java.sql.Date getDate(Object k) throws SQLException {
			Object v = value(k);
			if (v == null)
				return null;
			if (v instanceof java.util.Date d)
				return new java.sql.Date(d.getTime());
			if (v instanceof LocalDateTime d)
				return java.sql.Date.valueOf(d.toLocalDate());
			return java.sql.Date.valueOf((LocalDate) v);
		}

		default <T> T getObject(Object k, Class<T> type) throws SQLException {
			return type.cast(value(k));
		}
	}

	int update(String sql, Object... args);

	<T> List<T> query(String sql, Mapper<T> mapper, Object... args);

	List<Map<String, Object>> queryForList(String sql, Object... args);

	default <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
		return query(sql, (r, n) -> convert(r.value(1), type), args);
	}

	default <T> T queryForObject(String sql, Class<T> type, Object... args) {
		return one(queryForList(sql, type, args));
	}

	default <T> T queryForObject(String sql, Mapper<T> mapper, Object... args) {
		return one(query(sql, mapper, args));
	}

	default Map<String, Object> queryForMap(String sql, Object... args) {
		return one(queryForList(sql, args));
	}

	private static <T> T one(List<T> rows) {
		if (rows.size() != 1)
			throw new IncorrectResultSizeDataAccessException(1, rows.size());
		return rows.getFirst();
	}

	private static <T> T convert(Object v, Class<T> type) throws SQLException {
		if (v instanceof Clob c)
			v = c.getSubString(1, Math.toIntExact(c.length()));
		if (v == null)
			return null;
		if (type == String.class)
			return type.cast(v.toString());
		if (type == Integer.class && v instanceof Number n)
			return type.cast(n.intValue());
		if (type == Long.class && v instanceof Number n)
			return type.cast(n.longValue());
		return type.cast(v);
	}

	String buildSnapshot(String app, int revision, LocalDate asOf, String batch);
}
