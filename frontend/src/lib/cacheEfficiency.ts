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
// The single definition of cache efficiency for the whole dashboard.
//
// Cache efficiency = cacheRead / (input + cacheCreation + cacheRead): the share of
// input-side tokens served from the prompt cache. Output tokens are generated
// rather than sent, so they are excluded from the denominator; cache-creation
// tokens are deliberately kept in it, because dropping them would let a session
// that constantly rebuilds its cache read as efficient when it is in fact paying
// full freight to do so.
//
// Three backend expressions compute the identical ratio and must move with this
// file if the definition ever changes:
//   - MetricService.aggregateTokenUsage[InRange] — the window-level cacheReadRatio
//     shown on the Tokens page gauge.
//   - MetricPointRepository.aggregateSessionSummaries — the whitelisted
//     `cacheEfficiency` ORDER BY that sorts the Sessions grid column.
//   - MetricPointRepository.aggregateWorstCacheEfficiencySessions — the ranking
//     behind the Tokens page's worst-sessions list.
// If they disagree, the visible sort order stops matching the visible numbers.
//
// One frontend caller wraps rather than reimplements: `cacheHitRatePercent` in
// pages/TracesPage/tokenBreakdown.ts (the "N% cached" chips on the trace detail
// header and the span inspector) delegates here, bridging the field-name
// difference — its `TokenBreakdown` calls the field `cacheCreate`, not
// `cacheCreation` — and rounding to a whole percent. Keep it a wrapper; a second
// copy of the arithmetic is exactly what this module exists to prevent.
//
// A note on sources, since the same ratio is computed over three of them: the
// Sessions/Tokens figures come from the cumulative `claude_code.token.usage`
// counter, while the trace chips come from `claude_code.llm_request` span
// attributes — which carry the same exact per-request numbers as the
// `api_request` logs behind T3 (verified equal wherever both exist). The ratio is
// comparable across all of them; the absolute token totals are NOT (see the
// two-pipelines note in backend/CLAUDE.md).

import type { SessionTokenBreakdown } from '../api';

/** At or above this share of input-side tokens cached, context placement is working. */
export const CACHE_EFFICIENCY_STRONG = 0.85;

/** Below this, most turns are paying full freight to rebuild context. */
export const CACHE_EFFICIENCY_WEAK = 0.6;

export type CacheEfficiencyBand = 'strong' | 'mixed' | 'weak' | 'unknown';

/**
 * Ratio for a four-way token breakdown, or null when the subject recorded no
 * input-side tokens at all (efficiency is undefined, not zero — callers render
 * "—"). Null input returns null so callers can pass an optional breakdown
 * straight through.
 */
export const cacheEfficiencyRatio = (
  breakdown: SessionTokenBreakdown | null | undefined,
): number | null => {
  if (!breakdown) {
    return null;
  }
  const inputSideTokens =
    breakdown.input + breakdown.cacheCreation + breakdown.cacheRead;
  if (inputSideTokens <= 0) {
    return null;
  }
  return breakdown.cacheRead / inputSideTokens;
};

/** Band for a ratio; 'unknown' for null / non-finite (the "—" rows). */
export const cacheEfficiencyBand = (ratio: number | null): CacheEfficiencyBand => {
  if (ratio == null || !Number.isFinite(ratio)) {
    return 'unknown';
  }
  if (ratio >= CACHE_EFFICIENCY_STRONG) {
    return 'strong';
  }
  if (ratio >= CACHE_EFFICIENCY_WEAK) {
    return 'mixed';
  }
  return 'weak';
};

/** Ratio (0..1) → whole-percent string; "—" for null / non-finite input. */
export const formatCacheEfficiency = (ratio: number | null): string => {
  if (ratio == null || !Number.isFinite(ratio)) {
    return '—';
  }
  return `${Math.round(ratio * 100)}%`;
};
