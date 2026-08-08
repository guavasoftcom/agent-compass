import { describe, expect, it } from 'vitest';
import { cacheHitRatePercent, type TokenBreakdown } from './tokenBreakdown';

const breakdown = (parts: Partial<TokenBreakdown>): TokenBreakdown => {
  const input = parts.input ?? 0;
  const output = parts.output ?? 0;
  const cacheCreate = parts.cacheCreate ?? 0;
  const cacheRead = parts.cacheRead ?? 0;
  return {
    input,
    output,
    cacheCreate,
    cacheRead,
    total: input + output + cacheCreate + cacheRead,
  };
};

describe('cacheHitRatePercent', () => {
  it('is the cache-read share of the input-side tokens', () => {
    expect(
      cacheHitRatePercent(breakdown({ input: 21044, cacheRead: 158220 })),
    ).toBe(88);
  });

  it('excludes output tokens from the denominator', () => {
    // Output is generated, never cacheable, so a large output must not drag
    // the rate down: both of these are 50% input-side hits.
    const withoutOutput = breakdown({ input: 100, cacheRead: 100 });
    const withOutput = breakdown({ input: 100, cacheRead: 100, output: 9000 });
    expect(cacheHitRatePercent(withoutOutput)).toBe(50);
    expect(cacheHitRatePercent(withOutput)).toBe(50);
  });

  it('counts cache creation as input-side, not as a hit', () => {
    expect(
      cacheHitRatePercent(breakdown({ input: 50, cacheCreate: 50, cacheRead: 0 })),
    ).toBe(0);
  });

  it('is null when there were no input-side tokens to hit', () => {
    expect(cacheHitRatePercent(breakdown({ output: 500 }))).toBeNull();
    expect(cacheHitRatePercent(breakdown({}))).toBeNull();
  });
});
