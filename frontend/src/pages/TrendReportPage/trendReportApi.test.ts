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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resolveTrendReportSelection } from './trendReportApi';

// Aug 30, 2026, 3:30 PM local — mid-afternoon so day-snapping behavior is unambiguous
// from an exact-instant rolling window.
const MOCKED_NOW = new Date(2026, 7, 30, 15, 30, 0, 0);

describe('resolveTrendReportSelection', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(MOCKED_NOW);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('leaves a preset of 24 hours or less unchanged (exact rolling instant)', () => {
    const selection = { kind: 'preset' as const, minutes: 60 };
    expect(resolveTrendReportSelection(selection)).toEqual(selection);

    const oneDaySelection = { kind: 'preset' as const, minutes: 60 * 24 };
    expect(resolveTrendReportSelection(oneDaySelection)).toEqual(oneDaySelection);
  });

  it('snaps a preset over 24 hours to whole calendar days ending today', () => {
    const result = resolveTrendReportSelection({ kind: 'preset', minutes: 60 * 24 * 7 });
    expect(result.kind).toBe('custom');
    if (result.kind !== 'custom') {
      throw new Error('expected custom selection');
    }
    // Last 7 full calendar days: Aug 24 00:00:00.000 through Aug 30 23:59:59.999.
    expect(result.startTimestamp).toBe(new Date(2026, 7, 24, 0, 0, 0, 0).toISOString());
    expect(result.endTimestamp).toBe(new Date(2026, 7, 30, 23, 59, 59, 999).toISOString());
  });

  it('snaps a 30-day preset to 30 whole calendar days ending today', () => {
    const result = resolveTrendReportSelection({ kind: 'preset', minutes: 60 * 24 * 30 });
    expect(result.kind).toBe('custom');
    if (result.kind !== 'custom') {
      throw new Error('expected custom selection');
    }
    expect(result.startTimestamp).toBe(new Date(2026, 7, 1, 0, 0, 0, 0).toISOString());
    expect(result.endTimestamp).toBe(new Date(2026, 7, 30, 23, 59, 59, 999).toISOString());
  });

  it('leaves a custom range of 24 hours or less unchanged', () => {
    const selection = {
      kind: 'custom' as const,
      startTimestamp: new Date(2026, 7, 30, 8, 0, 0, 0).toISOString(),
      endTimestamp: new Date(2026, 7, 30, 20, 0, 0, 0).toISOString(),
    };
    expect(resolveTrendReportSelection(selection)).toEqual(selection);
  });

  it('snaps a custom range over 24 hours to whole calendar days', () => {
    const result = resolveTrendReportSelection({
      kind: 'custom',
      startTimestamp: new Date(2026, 7, 1, 8, 15, 0, 0).toISOString(),
      endTimestamp: new Date(2026, 7, 5, 14, 45, 0, 0).toISOString(),
    });
    expect(result.kind).toBe('custom');
    if (result.kind !== 'custom') {
      throw new Error('expected custom selection');
    }
    expect(result.startTimestamp).toBe(new Date(2026, 7, 1, 0, 0, 0, 0).toISOString());
    expect(result.endTimestamp).toBe(new Date(2026, 7, 5, 23, 59, 59, 999).toISOString());
  });

  it('is a no-op for a custom range already snapped to whole calendar days', () => {
    const selection = {
      kind: 'custom' as const,
      startTimestamp: new Date(2026, 7, 1, 0, 0, 0, 0).toISOString(),
      endTimestamp: new Date(2026, 7, 10, 23, 59, 59, 999).toISOString(),
    };
    expect(resolveTrendReportSelection(selection)).toEqual(selection);
  });
});
