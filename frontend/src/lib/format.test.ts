import { describe, expect, it } from 'vitest';
import { formatBytes, shortModelName } from './format';

describe('formatBytes', () => {
  it('renders raw bytes without decimals', () => {
    expect(formatBytes(512)).toBe('512 B');
  });

  it('steps up through binary units with one decimal', () => {
    expect(formatBytes(1024)).toBe('1 KB');
    expect(formatBytes(184_320)).toBe('180 KB');
    expect(formatBytes(1_572_864)).toBe('1.5 MB');
  });

  it('caps at the largest known unit rather than inventing one', () => {
    expect(formatBytes(1024 ** 5)).toBe('1024 TB');
  });

  it('renders zero and non-finite input as 0 B', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(Number.NaN)).toBe('0 B');
    expect(formatBytes(-1)).toBe('0 B');
  });
});

describe('shortModelName', () => {
  it('strips the vendor prefix and title-cases the family', () => {
    expect(shortModelName('claude-sonnet-4')).toBe('Sonnet 4');
  });

  it('leaves an unrecognized id alone', () => {
    expect(shortModelName('')).toBe('');
  });
});
