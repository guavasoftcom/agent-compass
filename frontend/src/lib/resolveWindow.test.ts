import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MAX_WINDOW_SPAN_MS, MS_PER_MINUTE, WINDOWS } from './constants';
import { resolveWindow } from './resolveWindow';

describe('resolveWindow', () => {
  const fixedNowMs = Date.parse('2026-06-15T12:00:00.000Z');

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(fixedNowMs);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('clamps the 30-day preset span to exactly MAX_WINDOW_SPAN_MS, never exceeding it', () => {
    const thirtyDayOption = WINDOWS.find((option) => option.label === '30 days');
    if (!thirtyDayOption) {
      throw new Error('expected a 30-day preset in WINDOWS');
    }

    const resolved = resolveWindow({ kind: 'preset', minutes: thirtyDayOption.value });

    const spanMs = Date.parse(resolved.endTimestamp) - Date.parse(resolved.startTimestamp);
    expect(spanMs).toBe(MAX_WINDOW_SPAN_MS);
    expect(spanMs).toBeLessThanOrEqual(MAX_WINDOW_SPAN_MS);
    expect(resolved.endTimestamp).toBe(new Date(fixedNowMs + MS_PER_MINUTE).toISOString());
    expect(resolved.label).toBe('30 days');
  });

  it('keeps start = end - minutes*60_000 with the +1min lookahead for a shorter preset', () => {
    const twentyFourHourOption = WINDOWS.find((option) => option.label === '24 hours');
    if (!twentyFourHourOption) {
      throw new Error('expected a 24-hour preset in WINDOWS');
    }

    const resolved = resolveWindow({ kind: 'preset', minutes: twentyFourHourOption.value });

    const expectedEndMs = fixedNowMs + MS_PER_MINUTE;
    const expectedStartMs = expectedEndMs - twentyFourHourOption.value * MS_PER_MINUTE;
    expect(resolved.endTimestamp).toBe(new Date(expectedEndMs).toISOString());
    expect(resolved.startTimestamp).toBe(new Date(expectedStartMs).toISOString());
    expect(resolved.label).toBe('24 hours');
  });

  it('passes a custom selection through untouched', () => {
    const startTimestamp = '2026-05-01T00:00:00.000Z';
    const endTimestamp = '2026-05-15T00:00:00.000Z';

    const resolved = resolveWindow({ kind: 'custom', startTimestamp, endTimestamp });

    expect(resolved.startTimestamp).toBe(startTimestamp);
    expect(resolved.endTimestamp).toBe(endTimestamp);
    expect(resolved.label).toBe('selected range');
  });
});
