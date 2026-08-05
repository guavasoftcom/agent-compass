// Compact number formatter (e.g. 12.3K, 4.5M) shared by the token and metric
// trend cards so large counts render identically across the dashboard.
const COMPACT_NUMBER_FORMATTER = new Intl.NumberFormat('en-US', {
  notation: 'compact',
  maximumFractionDigits: 1,
});

export const formatCompact = (value: number): string => COMPACT_NUMBER_FORMATTER.format(value);

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
