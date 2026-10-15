package com.rydex.ride.crypto;

import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Encrypts accounts whose email and phone are still in plain text: rows written before V7 and the
 * demo accounts, which the SQL seed inserts in plain text because SQL cannot reach the key. It runs
 * right after the migrations, before the service takes requests, so plain text never lingers.
 *
 * <p>It works on the bare DataSource: Spring's JDBC and JPA beans wait for the migrations, which
 * wait for this. Each row is one idempotent update, so a crash halfway is finished by the next start.
 */
@Component
public class PiiProtector {

    private static final Logger log = LoggerFactory.getLogger(PiiProtector.class);

    private final JdbcClient jdbc;
    private final FieldCipher cipher;

    PiiProtector(DataSource dataSource, FieldCipher cipher) {
        this.jdbc = JdbcClient.create(dataSource);
        this.cipher = cipher;
    }

    private record Plain(UUID id, String email, String phone) {
    }

    /** Returns how many accounts it encrypted. */
    public int encryptPlaintextRows() {
        var rows = jdbc.sql("SELECT id, email, phone FROM users WHERE email IS NOT NULL").query(Plain.class).list();
        int count = 0;
        for (Plain row : rows) {
            count += jdbc.sql("""
                    UPDATE users SET email_hash = :hash, email_enc = :email, phone_enc = :phone,
                                     email = NULL, phone = NULL
                    WHERE id = :id AND email IS NOT NULL""")
                    .param("hash", cipher.emailIndex(row.email()))
                    .param("email", cipher.encrypt(FieldCipher.normaliseEmail(row.email())))
                    .param("phone", cipher.encrypt(row.phone()))
                    .param("id", row.id())
                    .update();
        }
        if (count > 0) {
            log.info("Encrypted the email and phone of {} accounts", count);
        }
        return count;
    }

    @Configuration(proxyBeanMethods = false)
    static class MigrateThenProtect {

        /** Flyway first (schema + seed), then encrypt what the seed wrote. */
        @Bean
        FlywayMigrationStrategy migrateThenProtect(PiiProtector protector) {
            return flyway -> {
                flyway.migrate();
                protector.encryptPlaintextRows();
            };
        }
    }
}
