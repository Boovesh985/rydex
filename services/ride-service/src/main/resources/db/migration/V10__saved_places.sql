-- A rider's Home and Work (one each). The place (coordinates and address) is personal data:
-- encrypted like email and phone (ADR 0014), as one JSON value.
CREATE TABLE saved_places (
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       varchar(8)  NOT NULL CHECK (kind IN ('HOME', 'WORK')),
    place_enc  text        NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, kind)
);
