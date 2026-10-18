-- Every smart suggestion shown to a rider (master prompt §9), and what came of it: the feedback for
-- retraining and the acceptance rate. accepted: true = booked with it, false = dismissed, null = neither.
CREATE TABLE ai_suggestion_log (
    id            uuid PRIMARY KEY,
    user_id       uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    request       jsonb       NOT NULL,
    response      jsonb       NOT NULL,
    model_version varchar(40) NOT NULL,
    accepted      boolean,
    ride_id       uuid,
    created_at    timestamptz NOT NULL,
    decided_at    timestamptz
);

CREATE INDEX ai_suggestion_log_created_idx ON ai_suggestion_log (created_at);
CREATE INDEX ai_suggestion_log_user_idx ON ai_suggestion_log (user_id, created_at DESC);
