package com.rydex.ride.user;

import com.rydex.ride.crypto.EncryptedString;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An account. Email and phone are encrypted at rest; {@code emailHash} (a keyed HMAC of the
 * normalised email) finds the account at sign-in (ADR 0014).
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "email_hash", nullable = false)
    private String emailHash;

    @Convert(converter = EncryptedString.class)
    @Column(name = "email_enc", nullable = false)
    private String email;

    @Convert(converter = EncryptedString.class)
    @Column(name = "phone_enc")
    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** An automated (NPC) account the simulator plays (ADR 0016); seeded, never set by sign-up. */
    @Column(nullable = false)
    private boolean simulated;

    protected User() {
    }

    public User(String name, String email, String emailHash, String phone, String passwordHash, Role role,
                Instant createdAt) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.email = email;
        this.emailHash = emailHash;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    /** Re-hashes with the current algorithm (bcrypt accounts move to Argon2id on sign-in). */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean isSimulated() {
        return simulated;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
