// Formatters shared between SessionsKpiStrip and SessionsTable.

export const USD_FORMATTER = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export const USD_PER_MINUTE_FORMATTER = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 3,
  maximumFractionDigits: 3,
});

// Seconds → human-readable string: "45s" / "2m 30s" / "1h 5m".
// Note: this formats wall-clock seconds, not nanoseconds — it is distinct from
// TracesPage's nanosecond formatDuration and should not be shared with it.
export const formatDuration = (seconds: number): string => {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return '—';
  }
  if (seconds < 60) {
    return `${Math.round(seconds)}s`;
  }
  if (seconds < 3600) {
    const minutes = Math.floor(seconds / 60);
    const remainder = Math.round(seconds % 60);
    return remainder === 0 ? `${minutes}m` : `${minutes}m ${remainder}s`;
  }
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.round((seconds % 3600) / 60);
  return minutes === 0 ? `${hours}h` : `${hours}h ${minutes}m`;
};

// Compact token formatter (raw int → "5.4M" / "820K"); matches the Cost column's
// raw-number / client-format convention used across the Sessions page. Shared
// by the grid's Tokens column and PromptTimelinePanel's per-turn token line so
// both the K/M rounding and the zero-case ("—") stay in exact lockstep.
export const formatTokens = (value: number): string => {
  if (!Number.isFinite(value) || value <= 0) {
    return '—';
  }
  // Round-trip through the K bucket first: a value like 999,600 rounds to
  // "1000K" if formatted directly against the 1e6 threshold, so promote
  // anything that rounds up to 1000K into the M bucket instead.
  const thousands = Math.round(value / 1e3);
  if (value >= 1e6 || thousands >= 1000) {
    return `${(value / 1e6).toFixed(1).replace(/\.0$/, '')}M`;
  }
  if (value >= 1e3) {
    return `${thousands}K`;
  }
  return `${Math.round(value)}`;
};

export const formatTimestamp = (value: string): string =>
  value ? new Date(value).toLocaleString() : '';

// Relative "time ago" for the Last activity column (the default sort). Compact
// buckets — just now / Nm / Nh / Nd ago.
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
