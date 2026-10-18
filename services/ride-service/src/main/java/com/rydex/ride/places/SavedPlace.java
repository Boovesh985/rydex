package com.rydex.ride.places;

import com.rydex.ride.crypto.EncryptedString;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** A rider's Home or Work. {@code place} is the place as JSON, encrypted at rest (ADR 0014). */
@Entity
@Table(name = "saved_places")
@IdClass(SavedPlace.Key.class)
class SavedPlace {

    enum Kind {
        HOME,
        WORK
    }

    record Key(UUID userId, Kind kind) implements Serializable {
        Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    private Kind kind;

    @Convert(converter = EncryptedString.class)
    @Column(name = "place_enc", nullable = false)
    private String place;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SavedPlace() {
    }

    SavedPlace(UUID userId, Kind kind, String place, Instant now) {
        this.userId = userId;
        this.kind = kind;
        this.place = place;
        this.updatedAt = now;
    }

    void moveTo(String place, Instant now) {
        this.place = place;
        this.updatedAt = now;
    }

    Kind getKind() {
        return kind;
    }

    String getPlace() {
        return place;
    }
}
