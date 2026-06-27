-- Per-span token total for the Trace Explorer Tokens column / sort.
-- Mirrors readNumericAttr() in frontend traceDetailHelpers.ts: per category take the
-- first present key across the gen_ai / anthropic / bare fallbacks, then sum the four
-- categories. IMMUTABLE so it can be used inside aggregates and (optionally) indexed.
CREATE OR REPLACE FUNCTION span_token_total(span_attributes jsonb)
RETURNS numeric
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT
    COALESCE(
      (span_attributes->>'gen_ai.usage.input_tokens')::numeric,
      (span_attributes->>'gen_ai.usage.prompt_tokens')::numeric,
      (span_attributes->>'anthropic.usage.input_tokens')::numeric,
      (span_attributes->>'input_tokens')::numeric,
      0)
  + COALESCE(
      (span_attributes->>'gen_ai.usage.output_tokens')::numeric,
      (span_attributes->>'gen_ai.usage.completion_tokens')::numeric,
      (span_attributes->>'anthropic.usage.output_tokens')::numeric,
      (span_attributes->>'output_tokens')::numeric,
      0)
  + COALESCE(
      (span_attributes->>'gen_ai.usage.cache_creation_input_tokens')::numeric,
      (span_attributes->>'gen_ai.usage.cache_creation_tokens')::numeric,
      (span_attributes->>'anthropic.usage.cache_creation_input_tokens')::numeric,
      (span_attributes->>'anthropic.usage.cache_creation_tokens')::numeric,
      (span_attributes->>'cache_creation_input_tokens')::numeric,
      (span_attributes->>'cache_creation_tokens')::numeric,
      0)
  + COALESCE(
      (span_attributes->>'gen_ai.usage.cache_read_input_tokens')::numeric,
      (span_attributes->>'gen_ai.usage.cache_read_tokens')::numeric,
      (span_attributes->>'anthropic.usage.cache_read_input_tokens')::numeric,
      (span_attributes->>'anthropic.usage.cache_read_tokens')::numeric,
      (span_attributes->>'cache_read_input_tokens')::numeric,
      (span_attributes->>'cache_read_tokens')::numeric,
      0)
$$;
