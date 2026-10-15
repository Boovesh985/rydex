package com.rydex.ride.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.rydex.ride.IntegrationTest;
import com.rydex.ride.crypto.FieldCipher;
import com.rydex.ride.crypto.PiiProtector;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Personal data at rest, password hashing and the per-account lockout (ADR 0014). */
class AccountSecurityTests extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FieldCipher cipher;

    @Autowired
    PiiProtector protector;

    record Row(String email, String phone, String emailHash, String emailEnc, String phoneEnc, String passwordHash) {
    }

    private Row row(UUID id) {
        return jdbc.sql("""
                        SELECT email, phone, email_hash, email_enc, phone_enc, password_hash
                        FROM users WHERE id = :id""")
                .param("id", id).query(Row.class).single();
    }

    @Test
    void emailAndPhoneAreStoredEncryptedAndPasswordsWithArgon2id() {
        String email = "Enc-" + UUID.randomUUID() + "@Test.Rydex.dev";
        var res = api().post("/api/auth/register", null, Map.of(
                "name", "Encrypted", "email", email, "phone", "+919812345678", "password", "Sup3rSecret!",
                "role", "RIDER"));
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        Row r = row(UUID.fromString(res.getBody().path("user").path("id").asString()));

        assertThat(r.email()).isNull();
        assertThat(r.phone()).isNull();
        assertThat(r.emailEnc()).startsWith("v1.").doesNotContainIgnoringCase("enc-");
        assertThat(r.phoneEnc()).startsWith("v1.").doesNotContain("9812345678");
        assertThat(r.emailHash()).isEqualTo(cipher.emailIndex(email)).hasSize(64);
        assertThat(cipher.decrypt(r.emailEnc())).isEqualTo(email.toLowerCase());
        assertThat(r.passwordHash()).startsWith("{argon2}$argon2id$");
        // the API still shows the account its own details
        assertThat(res.getBody().path("user").path("email").asString()).isEqualTo(email.toLowerCase());
        assertThat(res.getBody().path("user").path("phone").asString()).isEqualTo("+919812345678");
        // and sign-in finds it by any casing
        var login = api().post("/api/auth/login", null, Map.of("email", email.toUpperCase(), "password", "Sup3rSecret!"));
        assertThat(login.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void seededAccountsAreEncryptedAndTheirBcryptHashUpgradedAtSignIn() {
        UUID rider4 = UUID.fromString("00000000-0000-4000-8000-000000000104");
        Row before = row(rider4);
        assertThat(before.email()).isNull();
        assertThat(cipher.decrypt(before.emailEnc())).isEqualTo("rider4@rydex.dev");
        assertThat(cipher.decrypt(before.phoneEnc())).isEqualTo("+919100000004");

        var login = api().post("/api/auth/login", null, Map.of("email", "rider4@rydex.dev", "password", "Rydex@123"));

        assertThat(login.getStatusCode().value()).isEqualTo(200);
        assertThat(row(rider4).passwordHash()).startsWith("{argon2}");
        assertThat(api().post("/api/auth/login", null, Map.of("email", "rider4@rydex.dev", "password", "Rydex@123"))
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void plainTextRowsAreEncryptedByTheProtector() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO users (id, name, email, phone, password_hash, role)
                VALUES (:id, 'Legacy', 'Legacy-User@test.rydex.dev', '+919000011111', 'x', 'RIDER')""")
                .param("id", id).update();

        assertThat(protector.encryptPlaintextRows()).isGreaterThanOrEqualTo(1);

        Row r = row(id);
        assertThat(r.email()).isNull();
        assertThat(r.phone()).isNull();
        assertThat(cipher.decrypt(r.emailEnc())).isEqualTo("legacy-user@test.rydex.dev");
        assertThat(r.emailHash()).isEqualTo(cipher.emailIndex("legacy-user@test.rydex.dev"));
        assertThat(protector.encryptPlaintextRows()).isZero();
    }

    @Test
    void anAccountLocksAfterTenWrongPasswordsEvenWithTheRightOne() {
        var auth = api().register("RIDER");
        String email = auth.path("user").path("email").asString();
        for (int i = 0; i < 10; i++) {
            assertThat(api().post("/api/auth/login", null, Map.of("email", email, "password", "Wrong-pass-" + i))
                    .getStatusCode().value()).isEqualTo(401);
        }

        var locked = api().post("/api/auth/login", null, Map.of("email", email, "password", "Sup3rSecret!"));

        assertThat(locked.getStatusCode().value()).isEqualTo(429);
        assertThat(Long.parseLong(locked.getHeaders().getFirst("Retry-After"))).isBetween(1L, 900L);
    }

    @Test
    void aSuccessfulSignInResetsTheFailureCount() {
        var auth = api().register("RIDER");
        String email = auth.path("user").path("email").asString();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 9; i++) {
                api().post("/api/auth/login", null, Map.of("email", email, "password", "Wrong-pass-" + i));
            }
            assertThat(api().post("/api/auth/login", null, Map.of("email", email, "password", "Sup3rSecret!"))
                    .getStatusCode().value()).isEqualTo(200);
        }
    }

    @Test
    void unknownEmailsLockTheSameWaySoTheLockoutRevealsNothing() {
        String email = "nobody-" + UUID.randomUUID() + "@test.rydex.dev";
        for (int i = 0; i < 10; i++) {
            api().post("/api/auth/login", null, Map.of("email", email, "password", "Wrong-pass-" + i));
        }
        assertThat(api().post("/api/auth/login", null, Map.of("email", email, "password", "Whatever-1"))
                .getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void weakPasswordsAreRefused() {
        String email = "weak-" + UUID.randomUUID() + "@test.rydex.dev";
        var noDigit = api().post("/api/auth/register", null, Map.of(
                "name", "Weak", "email", email, "password", "onlyletters", "role", "RIDER"));
        var noLetter = api().post("/api/auth/register", null, Map.of(
                "name", "Weak", "email", email, "password", "1234567890", "role", "RIDER"));
        var tooLong = api().post("/api/auth/register", null, Map.of(
                "name", "Weak", "email", email, "password", "a1".repeat(65), "role", "RIDER"));
        var containsEmail = api().post("/api/auth/register", null, Map.of(
                "name", "Weak", "email", "boovesh@test.rydex.dev", "password", "Boovesh2026", "role", "RIDER"));

        assertThat(noDigit.getStatusCode().value()).isEqualTo(400);
        assertThat(noLetter.getStatusCode().value()).isEqualTo(400);
        assertThat(tooLong.getStatusCode().value()).isEqualTo(400);
        assertThat(containsEmail.getStatusCode().value()).isEqualTo(422);
    }
}
