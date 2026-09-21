-- Singleton row holding the Settings page's "check for updates" toggle. A null enabled = no
-- override, falls back to the update-check.enabled application.yml default (the same shape
-- ollama_settings uses, V22/V29); the toggle itself has no blank state, so in practice a save
-- always pins an explicit true/false once touched.
CREATE TABLE update_check_settings (
    id         SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled    BOOLEAN,
    updated_at TIMESTAMPTZ NOT NULL
);
