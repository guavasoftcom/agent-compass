-- GIN indexes on the jsonb attribute bags. Every Phase 1 dashboard filters on
-- `attributes ->> '<key>'`, which is a seq scan without these. `jsonb_path_ops`
-- is the smaller / faster operator class for the containment-style lookups we
-- run (`attributes @> '{"tool_name":"Edit"}'`-shaped predicates).
CREATE INDEX idx_log_records_attributes_gin
    ON log_records USING gin (attributes jsonb_path_ops);

CREATE INDEX idx_metric_points_attributes_gin
    ON metric_points USING gin (attributes jsonb_path_ops);
