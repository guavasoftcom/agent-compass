-- Singleton row of runtime-editable Ollama connection overrides for the Settings page. Nullable
-- base_url/model = no override for that field (falls back independently to the ollama.base-url /
-- ollama.model application.yml defaults) -- chosen over an all-or-nothing row so an operator can
-- override just the model (e.g. try qwen2.5:14b) without also having to restate the default host.
CREATE TABLE ollama_settings (
    id         SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    base_url   VARCHAR(255),
    model      VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL
);
