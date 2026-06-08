// Sample data for the Token Usage page.
//
// The cache-read ratio, token-mix donut, and time-series chart all run on the
// live `TokenUsageSummary` props. Cost and per-model token sums are NOT yet in
// the metrics API — these placeholders render until the endpoints documented in
// BACKEND.md (`/cost/summary`, `/token-usage/by-model`) land. Swap them for real
// data by passing props from the page container.

export interface TokenCostSummary {
  /** Headline spend over the window, pre-formatted (e.g. "$1,284"). */
  spend: string;
  /** Signed delta vs. the previous equal-length window (e.g. "+22.4%"). */
  deltaPct: string;
  /** Spend per hour (e.g. "$53"). */
  burnRate: string;
  /** Naive 30-day linear projection (e.g. "$38.5K"). */
  projected30d: string;
  /** Blended cost per 1K tokens (e.g. "$0.099"). */
  costPer1k: string;
  /** Spend-trend spark values (same bucketing as the time-series). */
  trend: number[];
}

export interface TokenByModelRow {
  model: string;
  /** Token sum over the window, pre-formatted (e.g. "7.8M"). */
  tokens: string;
  /** Share of total tokens, 0–100. */
  share: number;
  /** Index into the dashboard palette (theme.colorForIndex). */
  colorIndex: number;
}

export const TOKEN_COST: TokenCostSummary = {
  spend: '$1,284',
  deltaPct: '+22.4%',
  burnRate: '$53',
  projected30d: '$38.5K',
  costPer1k: '$0.099',
  trend: [28, 34, 30, 40, 42, 50, 46, 58, 52, 60, 66, 72, 68, 78],
};

export const TOKEN_BY_MODEL: TokenByModelRow[] = [
  { model: 'claude-sonnet-4', tokens: '7.8M', share: 60, colorIndex: 0 },
  { model: 'claude-opus-4', tokens: '3.6M', share: 28, colorIndex: 1 },
  { model: 'claude-haiku-3.5', tokens: '1.6M', share: 12, colorIndex: 2 },
];

export const TOKEN_BY_MODEL_NOTE =
  'opus-4 is 28% of tokens but ~56% of spend — its calls cost ~4× sonnet per token. '
  + 'Shifting planning turns to sonnet would trim the bill most.';
