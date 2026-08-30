/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
import type { SpanRow } from '../../api';
import { cacheEfficiencyRatio } from '../../lib/cacheEfficiency';

const PERCENT_SCALE = 100;
// A cache hit rate above this prints as ">99%" rather than rounding to 100%.
const NEAR_COMPLETE_PERCENT = 99;
// Below this a share prints as "<0.1%"; at or above the upper bound, ">99.9%".
const SHARE_FLOOR_PERCENT = 0.1;
const SHARE_CEILING_PERCENT = 99.95;
// One decimal below this, none above — 3.2% is worth a decimal, 84% is not.
const SHARE_DECIMAL_CUTOFF = 10;

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

// The tokens priced at the full input/output rate — everything except cache
// read, which bills at a tenth of the input rate. The trace summary's top track
// and the waterfall row's loud token chip both scale to this subtotal rather
// than to the four-way total, where cache read (routinely 10-1000x the rest)
// flattens every other figure into a hairline.
export const fullRateTokens = (tokenBreakdown: TokenBreakdown): number =>
  tokenBreakdown.input + tokenBreakdown.output + tokenBreakdown.cacheCreate;

// Unrounded percent, for cacheHitRateLabel's >99% guard — rounding first would
// destroy exactly the distinction the guard exists to keep.
const cacheHitRatePercentPrecise = (
  tokenBreakdown: TokenBreakdown,
): number | null => {
  const ratio = cacheEfficiencyRatio({
    input: tokenBreakdown.input,
    output: tokenBreakdown.output,
    cacheCreation: tokenBreakdown.cacheCreate,
    cacheRead: tokenBreakdown.cacheRead,
  });
  return ratio == null ? null : ratio * PERCENT_SCALE;
};

// Display form of cacheHitRatePercent, and the only form the chips should use.
// The rounding is deliberately asymmetric at the top of the range: on a real
// trace cache read is 99.x% of every input-side token, and a rounded "100%
// cached" reads as "nothing was billed" — which is wrong, and the difference
// between 99.4% and 100% is thousands of full-rate tokens. Only an exact 100%
// prints 100%.
export const cacheHitRateLabel = (
  tokenBreakdown: TokenBreakdown,
): string | null => {
  const percent = cacheHitRatePercentPrecise(tokenBreakdown);
  if (percent == null) {
    return null;
  }
  if (percent >= PERCENT_SCALE) {
    return '100%';
  }
  if (percent > NEAR_COMPLETE_PERCENT) {
    return `>${NEAR_COMPLETE_PERCENT}%`;
  }
  return `${Math.round(percent)}%`;
};

// One token kind's share of a trace's total, as a label. Clamped at both ends so
// a real cache-heavy trace reads honestly: a 40-token output row is "<0.1%", not
// "0%", and a cache-read row is ">99.9%", not "100%".
export const tokenShareLabel = (value: number, total: number): string => {
  if (value <= 0 || total <= 0) {
    return '0%';
  }
  const percent = (value / total) * PERCENT_SCALE;
  if (percent < SHARE_FLOOR_PERCENT) {
    return `<${SHARE_FLOOR_PERCENT}%`;
  }
  if (percent >= SHARE_CEILING_PERCENT) {
    return '>99.9%';
  }
  return `${percent.toFixed(percent < SHARE_DECIMAL_CUTOFF ? 1 : 0)}%`;
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
