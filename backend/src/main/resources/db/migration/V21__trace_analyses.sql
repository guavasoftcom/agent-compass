-- Stores the latest local-Ollama analysis of a trace. trace_id is the primary key (not a
-- surrogate id + unique constraint) so "one stored analysis per trace, regenerate overwrites
-- it" is a plain save()/upsert on the same key -- no delete-then-insert or history cleanup.
CREATE TABLE trace_analyses (
    trace_id                VARCHAR(32) PRIMARY KEY,
    model                   VARCHAR(255) NOT NULL,
    analysis_text           TEXT NOT NULL,
    generation_duration_ms  BIGINT NOT NULL,
    generated_at            TIMESTAMPTZ NOT NULL
);
