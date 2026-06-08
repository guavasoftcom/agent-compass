CREATE INDEX idx_log_records_session_id_ts
    ON log_records ((attributes ->> 'session.id'), timestamp);
