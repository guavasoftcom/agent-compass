// Compact number formatter (e.g. 12.3K, 4.5M) shared by the token and metric
// trend cards so large counts render identically across the dashboard.
const COMPACT_NUMBER_FORMATTER = new Intl.NumberFormat('en-US', {
  notation: 'compact',
  maximumFractionDigits: 1,
});

export const formatCompact = (value: number): string => COMPACT_NUMBER_FORMATTER.format(value);
