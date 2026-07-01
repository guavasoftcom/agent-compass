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
// raw-number / client-format convention used across the Sessions page.
export const formatTokens = (n: number): string => {
  if (!Number.isFinite(n) || n <= 0) {
    return '—';
  }
  if (n >= 1e6) {
    return `${(n / 1e6).toFixed(1).replace(/\.0$/, '')}M`;
  }
  if (n >= 1e3) {
    return `${Math.round(n / 1e3)}K`;
  }
  return `${Math.round(n)}`;
};

export const formatTimestamp = (value: string): string =>
  value ? new Date(value).toLocaleString() : '';
