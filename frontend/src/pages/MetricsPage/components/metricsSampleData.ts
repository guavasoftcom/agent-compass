// Sample data for the simplified Metrics page.
//
// Six fixed counters in the claude_code.* namespace. Each carries a headline
// value, a 24-point trend (for the catalog sparkline + the detail chart), and an
// optional set of attribute "splits" (e.g. token usage by model or type). These
// render until the API lands — see BACKEND.md (`GET /api/metrics/series`).

export interface MetricSplitRow {
  label: string;
  /** Pre-formatted display value, e.g. "7.8M" or "$720". */
  value: string;
  /** Share of the metric total, 0–100. */
  pct: number;
  /** Index into the dashboard palette (theme.colorForIndex). */
  colorIndex: number;
}

export interface MetricSeries {
  id: string;
  /** Fully-qualified metric name, e.g. "claude_code.token.usage". */
  name: string;
  type: 'counter' | 'gauge' | 'histogram';
  /** Unit label, e.g. "tokens", "USD", "{session}". */
  unit: string;
  /** Headline total over the window, pre-formatted. */
  sum: string;
  /** Caption for the headline stat, e.g. "Sum (24h)". */
  sumLabel: string;
  /** Per-hour rate value + its unit suffix, e.g. "542K" + "/h". */
  rate: string;
  rateUnit: string;
  /** Peak hourly value, pre-formatted. */
  peak: string;
  /** Signed change vs. the previous equal window, e.g. "+18.3%". */
  delta: string;
  dir: 'up' | 'down';
  /** One-line description (plain text). */
  description: string;
  /** 24-point trend over the window (newest last). */
  trend: number[];
  /** Attribute breakdowns, keyed by split name ("Model", "Type", …). Empty = no split. */
  splits: Record<string, MetricSplitRow[]>;
}

// Shared 24-point shape, scaled per metric so every sparkline reads naturally.
const SHAPE = [5, 6, 5, 7, 8, 7, 9, 8, 10, 9, 11, 10, 12, 11, 13, 12, 14, 13, 15, 14, 16, 15, 17, 18];
const scale = (factor: number): number[] => SHAPE.map((v) => v * factor);

export const METRICS: MetricSeries[] = [
  {
    id: 'token',
    name: 'claude_code.token.usage',
    type: 'counter',
    unit: 'tokens',
    sum: '13.0M',
    sumLabel: 'Sum (24h)',
    rate: '542K',
    rateUnit: '/h',
    peak: '820K',
    delta: '+18.3%',
    dir: 'up',
    description:
      'Tokens consumed across Claude Code sessions, summed over the window. The biggest cost driver — split by model or token type to see where they go.',
    trend: scale(52),
    splits: {
      Model: [
        { label: 'claude-sonnet-4', value: '7.8M', pct: 60, colorIndex: 0 },
        { label: 'claude-opus-4', value: '3.6M', pct: 28, colorIndex: 1 },
        { label: 'claude-haiku-3.5', value: '1.6M', pct: 12, colorIndex: 2 },
      ],
      Type: [
        { label: 'cache read', value: '8.9M', pct: 68, colorIndex: 2 },
        { label: 'input', value: '2.4M', pct: 18, colorIndex: 0 },
        { label: 'cache creation', value: '1.2M', pct: 9, colorIndex: 3 },
        { label: 'output', value: '486K', pct: 4, colorIndex: 1 },
      ],
    },
  },
  {
    id: 'cost',
    name: 'claude_code.cost.usage',
    type: 'counter',
    unit: 'USD',
    sum: '$1,284',
    sumLabel: 'Spend (24h)',
    rate: '$53',
    rateUnit: '/h',
    peak: '$96',
    delta: '+22.4%',
    dir: 'up',
    description:
      'Billed spend in USD over the window. Tracks the token surge — split by model to see which model drives the bill.',
    trend: scale(5.3),
    splits: {
      Model: [
        { label: 'claude-opus-4', value: '$720', pct: 56, colorIndex: 1 },
        { label: 'claude-sonnet-4', value: '$470', pct: 37, colorIndex: 0 },
        { label: 'claude-haiku-3.5', value: '$94', pct: 7, colorIndex: 2 },
      ],
    },
  },
  {
    id: 'session',
    name: 'claude_code.session.count',
    type: 'counter',
    unit: '{session}',
    sum: '56',
    sumLabel: 'Sessions (24h)',
    rate: '2.3',
    rateUnit: '/h',
    peak: '7',
    delta: '+4.0%',
    dir: 'up',
    description:
      'New Claude Code sessions started in the window. A simple volume signal for how much the CLI is being used.',
    trend: scale(0.42),
    splits: {},
  },
  {
    id: 'active',
    name: 'claude_code.active_time.total',
    type: 'counter',
    unit: 's',
    sum: '7.4h',
    sumLabel: 'Active (24h)',
    rate: '18m',
    rateUnit: '/h',
    peak: '41m',
    delta: '-6.2%',
    dir: 'down',
    description:
      'Total active engagement time, summed across sessions. Active time excludes idle gaps between turns.',
    trend: scale(1.6),
    splits: {},
  },
  {
    id: 'loc',
    name: 'claude_code.lines_of_code.count',
    type: 'counter',
    unit: '{line}',
    sum: '12.4K',
    sumLabel: 'Lines (24h)',
    rate: '517',
    rateUnit: '/h',
    peak: '1.2K',
    delta: '+31.0%',
    dir: 'up',
    description:
      'Lines of code added or removed by edit tools. Split by change to separate additions from removals.',
    trend: scale(52),
    splits: {
      Change: [
        { label: 'added', value: '9.1K', pct: 73, colorIndex: 3 },
        { label: 'removed', value: '3.3K', pct: 27, colorIndex: 1 },
      ],
    },
  },
  {
    id: 'decision',
    name: 'claude_code.code_edit_tool.decision',
    type: 'counter',
    unit: '{decision}',
    sum: '1,042',
    sumLabel: 'Decisions (24h)',
    rate: '43',
    rateUnit: '/h',
    peak: '88',
    delta: '+9.5%',
    dir: 'up',
    description:
      'Edit-tool permission decisions. Split by decision to see the accept / reject mix.',
    trend: scale(42),
    splits: {
      Decision: [
        { label: 'accepted', value: '903', pct: 87, colorIndex: 3 },
        { label: 'rejected', value: '139', pct: 13, colorIndex: 1 },
      ],
    },
  },
];
