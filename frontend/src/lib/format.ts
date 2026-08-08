// Compact number formatter (e.g. 12.3K, 4.5M) shared by the token and metric
// trend cards so large counts render identically across the dashboard.
const COMPACT_NUMBER_FORMATTER = new Intl.NumberFormat('en-US', {
  notation: 'compact',
  maximumFractionDigits: 1,
});

export const formatCompact = (value: number): string => COMPACT_NUMBER_FORMATTER.format(value);

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
