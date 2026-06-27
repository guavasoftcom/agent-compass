import type { SpanRow } from '../../api';

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
