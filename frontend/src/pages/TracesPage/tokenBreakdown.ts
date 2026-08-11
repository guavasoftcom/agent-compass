import type { SpanRow } from '../../api';
import { cacheEfficiencyRatio } from '../../lib/cacheEfficiency';

const PERCENT_SCALE = 100;

export interface TokenBreakdown {
  input: number;
  output: number;
  cacheCreate: number;
  cacheRead: number;
  total: number;
}

const INPUT_TOKEN_KEYS = [
  'gen_ai.usage.input_tokens',
  'gen_ai.usage.prompt_tokens',
  'anthropic.usage.input_tokens',
  'input_tokens',
] as const;

const OUTPUT_TOKEN_KEYS = [
  'gen_ai.usage.output_tokens',
  'gen_ai.usage.completion_tokens',
  'anthropic.usage.output_tokens',
  'output_tokens',
] as const;

const CACHE_CREATE_TOKEN_KEYS = [
  'gen_ai.usage.cache_creation_input_tokens',
  'gen_ai.usage.cache_creation_tokens',
  'anthropic.usage.cache_creation_input_tokens',
  'anthropic.usage.cache_creation_tokens',
  'cache_creation_input_tokens',
  'cache_creation_tokens',
] as const;

const CACHE_READ_TOKEN_KEYS = [
  'gen_ai.usage.cache_read_input_tokens',
  'gen_ai.usage.cache_read_tokens',
  'anthropic.usage.cache_read_input_tokens',
  'anthropic.usage.cache_read_tokens',
  'cache_read_input_tokens',
  'cache_read_tokens',
] as const;

const readNumericAttr = (
  attrs: Record<string, unknown>,
  keys: readonly string[],
): number => {
  for (const key of keys) {
    const value = attrs[key];
    if (typeof value === 'number' && Number.isFinite(value)) {
      return value;
    }
    if (typeof value === 'string') {
      const parsed = Number(value);
      if (Number.isFinite(parsed)) {
        return parsed;
      }
    }
  }
  return 0;
};

// Share of the input-side tokens that were served from the prompt cache, as a
// whole percent (or null when there were no input-side tokens at all — nothing
// to hit).
//
// The ratio itself comes from the shared lib/cacheEfficiency helper, which is the
// dashboard's single definition of cache efficiency; this wrapper exists only to
// bridge the field-name difference (`cacheCreate` here vs `cacheCreation` on
// SessionTokenBreakdown) and to round to a percent for the chips. Keeping the
// arithmetic in one place means the trace chips can't silently drift from the
// Sessions column and the Tokens page gauge, which is what they'd do if this
// stayed a second copy that merely happened to agree.
//
// Note the totals these chips describe are NOT comparable to the Sessions/Tokens
// token totals — those come from cumulative counters, these from per-request span
// attributes (see the two-pipelines note in backend/CLAUDE.md). The *ratio* is
// comparable; the absolute numbers are not.
export const cacheHitRatePercent = (
  tokenBreakdown: TokenBreakdown,
): number | null => {
  const ratio = cacheEfficiencyRatio({
    input: tokenBreakdown.input,
    output: tokenBreakdown.output,
    cacheCreation: tokenBreakdown.cacheCreate,
    cacheRead: tokenBreakdown.cacheRead,
  });
  return ratio == null ? null : Math.round(ratio * PERCENT_SCALE);
};

export const tokenBreakdownForSpan = (span: SpanRow): TokenBreakdown => {
  const attrs = span.attributes;
  if (!attrs) {
    return { input: 0, output: 0, cacheCreate: 0, cacheRead: 0, total: 0 };
  }
  const input = readNumericAttr(attrs, INPUT_TOKEN_KEYS);
  const output = readNumericAttr(attrs, OUTPUT_TOKEN_KEYS);
  const cacheCreate = readNumericAttr(attrs, CACHE_CREATE_TOKEN_KEYS);
  const cacheRead = readNumericAttr(attrs, CACHE_READ_TOKEN_KEYS);
  return {
    input,
    output,
    cacheCreate,
    cacheRead,
    total: input + output + cacheCreate + cacheRead,
  };
};
