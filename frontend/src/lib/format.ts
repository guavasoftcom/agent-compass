// Compact number formatter (e.g. 12.3K, 4.5M) shared by the token and metric
// trend cards so large counts render identically across the dashboard.
const COMPACT_NUMBER_FORMATTER = new Intl.NumberFormat('en-US', {
  notation: 'compact',
  maximumFractionDigits: 1,
});

export const formatCompact = (value: number): string => COMPACT_NUMBER_FORMATTER.format(value);

// USD currency formatter (e.g. "$4.23") shared across the Sessions and Tokens
// pages — costs render identically wherever a session or a token summary
// names a dollar figure. `sessionsFormat.ts` re-exports this rather than
// declaring its own copy.
export const USD_FORMATTER = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const BYTES_PER_UNIT = 1024;
const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

// Byte count → compact binary-unit string ("184 KB", "1.2 MB"). Used by the
// context-footprint card; sizes are shown to one decimal from KB up so a tool
// with 1.2 MB of output doesn't round to the same label as one with 1.9 MB.
export const formatBytes = (value: number): string => {
  if (!Number.isFinite(value) || value <= 0) {
    return '0 B';
  }
  let magnitude = value;
  let unitIndex = 0;
  while (magnitude >= BYTES_PER_UNIT && unitIndex < BYTE_UNITS.length - 1) {
    magnitude /= BYTES_PER_UNIT;
    unitIndex += 1;
  }
  const rounded = unitIndex === 0 ? Math.round(magnitude) : Number(magnitude.toFixed(1));
  return `${rounded} ${BYTE_UNITS[unitIndex]}`;
};

// Short, human display name for a model id, e.g. "claude-sonnet-4" → "Sonnet 4".
// Shared by every per-model breakdown (Token Usage, Skills & Subagents) so the
// same model reads identically wherever it appears.
export const shortModelName = (model: string): string => {
  const parts = model.replace(/^claude-/, '').split('-');
  if (parts.length === 0 || parts[0] === '') {
    return model;
  }
  const [family, ...rest] = parts;
  return [family.charAt(0).toUpperCase() + family.slice(1), ...rest].join(' ');
};

// Full locale timestamp, e.g. "5/27/2026, 1:07:15 AM" — shared by the Sessions
// grid and the Tokens page's cache-efficiency rank card/dialog so a session's
// absolute timestamp reads identically wherever it's shown. `sessionsFormat.ts`
// re-exports this rather than declaring its own copy (see the USD_FORMATTER
// note there).
export const formatTimestamp = (value: string): string =>
  value ? new Date(value).toLocaleString() : '';

// Relative "time ago" for a last-activity figure — compact buckets: just now /
// Nm / Nh / Nd ago. Shared by the same two surfaces as formatTimestamp.
export const formatRelativeTime = (value: string): string => {
  if (!value) {
    return '—';
  }
  const secondsAgo = Math.max(0, Math.round((Date.now() - new Date(value).getTime()) / 1000));
  if (secondsAgo < 45) {
    return 'just now';
  }
  if (secondsAgo < 90) {
    return '1m ago';
  }
  if (secondsAgo < 3600) {
    return `${Math.round(secondsAgo / 60)}m ago`;
  }
  if (secondsAgo < 5400) {
    return '1h ago';
  }
  if (secondsAgo < 86400) {
    return `${Math.round(secondsAgo / 3600)}h ago`;
  }
  return `${Math.round(secondsAgo / 86400)}d ago`;
};
