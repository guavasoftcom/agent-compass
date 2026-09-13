-- Adds the Settings page's Ollama connection Enabled/Disabled toggle as a third independently
-- overridable field on the ollama_settings singleton row, alongside base_url/model (V22). Null =
-- no override, falls back to the ollama.enabled application.yml default; the toggle control itself
-- has no "blank" state, so in practice a save always pins an explicit true/false once touched.
ALTER TABLE ollama_settings ADD COLUMN enabled BOOLEAN;
