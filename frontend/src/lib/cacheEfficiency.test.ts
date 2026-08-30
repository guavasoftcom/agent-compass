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
import { describe, expect, it } from 'vitest';
import {
  CACHE_EFFICIENCY_STRONG,
  CACHE_EFFICIENCY_WEAK,
  cacheEfficiencyBand,
  cacheEfficiencyRatio,
  formatCacheEfficiency,
} from './cacheEfficiency';

const breakdown = (
  input: number,
  cacheCreation: number,
  cacheRead: number,
  output = 0,
) => ({ input, output, cacheCreation, cacheRead });

describe('cacheEfficiencyRatio', () => {
  it('divides cache reads by all input-side tokens', () => {
    expect(cacheEfficiencyRatio(breakdown(10, 10, 80))).toBeCloseTo(0.8);
  });

  it('excludes output tokens from the denominator', () => {
    // Output is generated, never sent, so a large output must not dilute the ratio.
    const withoutOutput = cacheEfficiencyRatio(breakdown(10, 10, 80));
    const withOutput = cacheEfficiencyRatio(breakdown(10, 10, 80, 1_000_000));
    expect(withOutput).toBe(withoutOutput);
  });

  it('keeps cache-creation tokens in the denominator', () => {
    // A session that constantly rebuilds its cache is paying full freight to do
    // so and must not read as efficient.
    expect(cacheEfficiencyRatio(breakdown(0, 50, 50))).toBeCloseTo(0.5);
  });

  it('returns null when there are no input-side tokens', () => {
    expect(cacheEfficiencyRatio(breakdown(0, 0, 0, 500))).toBeNull();
  });

  it('returns null for a missing breakdown', () => {
    expect(cacheEfficiencyRatio(null)).toBeNull();
    expect(cacheEfficiencyRatio(undefined)).toBeNull();
  });
});

describe('cacheEfficiencyBand', () => {
  it('treats the thresholds as inclusive lower bounds', () => {
    expect(cacheEfficiencyBand(CACHE_EFFICIENCY_STRONG)).toBe('strong');
    expect(cacheEfficiencyBand(CACHE_EFFICIENCY_WEAK)).toBe('mixed');
  });

  it('bands values between and below the thresholds', () => {
    expect(cacheEfficiencyBand(0.95)).toBe('strong');
    expect(cacheEfficiencyBand(0.7)).toBe('mixed');
    expect(cacheEfficiencyBand(0.2)).toBe('weak');
  });

  it('reports unknown for null and non-finite ratios', () => {
    expect(cacheEfficiencyBand(null)).toBe('unknown');
    expect(cacheEfficiencyBand(Number.NaN)).toBe('unknown');
  });
});

describe('formatCacheEfficiency', () => {
  it('renders whole percents', () => {
    expect(formatCacheEfficiency(0.412)).toBe('41%');
    expect(formatCacheEfficiency(1)).toBe('100%');
  });

  it('renders an em dash for undefined efficiency', () => {
    expect(formatCacheEfficiency(null)).toBe('—');
    expect(formatCacheEfficiency(Number.NaN)).toBe('—');
  });
});
