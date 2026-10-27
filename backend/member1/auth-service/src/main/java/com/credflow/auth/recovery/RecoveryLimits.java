package com.credflow.auth.recovery;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryLimits {
    private final JdbcTemplate jdbc;
    public RecoveryLimits(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Commit limits independently, including when recovery validation later fails. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(String action, String address, String username) {
        jdbc.update("DELETE FROM CF_PASSWORD_RESET_LIMITS WHERE WINDOW_START < SYSTIMESTAMP - INTERVAL '1' DAY");
        boolean ip = count(PasswordRecoveryService.hash(action + ":ip:" + address)) <= 30;
        boolean account = username == null || count(PasswordRecoveryService.hash(action + ":user:" + username)) <= 5;
        return ip && account;
    }
    private int count(String key) {
        String update = "UPDATE CF_PASSWORD_RESET_LIMITS SET ATTEMPTS=CASE WHEN WINDOW_START<=SYSTIMESTAMP-INTERVAL '15' MINUTE THEN 1 ELSE ATTEMPTS+1 END,WINDOW_START=CASE WHEN WINDOW_START<=SYSTIMESTAMP-INTERVAL '15' MINUTE THEN SYSTIMESTAMP ELSE WINDOW_START END WHERE KEY_HASH=?";
        if (jdbc.update(update, key) == 0) {
            try { jdbc.update("INSERT INTO CF_PASSWORD_RESET_LIMITS(KEY_HASH,WINDOW_START,ATTEMPTS) VALUES (?,SYSTIMESTAMP,1)", key); }
            catch (DuplicateKeyException race) { jdbc.update(update, key); }
        }
        return jdbc.queryForObject("SELECT ATTEMPTS FROM CF_PASSWORD_RESET_LIMITS WHERE KEY_HASH=?", Integer.class, key);
    }
}
