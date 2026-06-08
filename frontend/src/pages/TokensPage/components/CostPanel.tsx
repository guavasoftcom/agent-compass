import { Box, Paper, Stack, useTheme } from '@mui/material';
import type { ReactNode } from 'react';
import { colorForIndex } from '../../../theme';

/**
 * Cost summary for the window. Pre-formatted display strings so the view stays
 * dumb. Sourced from the live `summary.cost` on the token-usage response
 * (see BACKEND.md) — never a fixture.
 */
export interface TokenCostSummary {
  /** Headline spend over the window, pre-formatted (e.g. "$1,284"). */
  spend: string;
  /** Signed delta vs. the previous equal-length window (e.g. "+22.4%"). */
  deltaPct: string;
  /** Spend per hour (e.g. "$53"); the UI appends "/h". */
  burnRate: string;
  /** Naive 30-day linear projection (e.g. "$38.5K"). */
  projected30d: string;
  /** Blended cost per 1K tokens (e.g. "$0.099"). */
  costPer1k: string;
  /** Spend-trend spark values (same bucketing as the time-series). */
  trend: number[];
  /** Optional estimated $ saved by cache reads (e.g. "$840"). */
  estimatedSavingsUsd?: string;
}

const Label = ({ children }: { children: ReactNode }) => (
  <Box
    sx={{
      fontSize: 11,
      fontWeight: 600,
      letterSpacing: 0.5,
      textTransform: 'uppercase',
      color: 'text.disabled',
    }}
  >
    {children}
  </Box>
);

const ArrowUp = ({ size = 14 }: { size?: number }) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={size} height={size}>
    <path d="M7 14l5-5 5 5" />
  </svg>
);

/** Tiny area sparkline (line + soft fill), matching the mockup's cost trend. */
const CostSpark = ({ values }: { values: number[] }) => {
  const theme = useTheme();
  const w = 700;
  const h = 52;
  const max = Math.max(...values);
  const min = Math.min(...values);
  const range = max - min || 1;
  const x = (i: number) => i * (w / (values.length - 1));
  const y = (v: number) => h - 3 - ((v - min) / range) * (h - 6);
  const line = values.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join('');
  const area = `${line}L${w},${h}L0,${h}Z`;
  const color = colorForIndex(4);
  return (
    <svg viewBox={`0 0 ${w} ${h}`} width="100%" height={h} preserveAspectRatio="none">
      <path d={area} fill={`${theme.palette.primary.main}1a`} />
      <path
        d={line}
        fill="none"
        stroke={color}
        strokeWidth={2}
        vectorEffect="non-scaling-stroke"
        strokeLinejoin="round"
      />
    </svg>
  );
};

export interface CostPanelProps {
  /** Live cost summary for the selected window (`summary.cost`). */
  data: TokenCostSummary;
  /** Window phrase for the period labels, e.g. "last 24 hours" — never hardcoded. */
  periodLabel: string;
  /** Cache-read tokens saved this window, pre-formatted (e.g. "8.9M"). */
  savedTokens: string;
}

/**
 * Cost panel for the Token Usage page: headline spend, burn rate, 30-day
 * projection, blended cost-per-1K, a trend sparkline, and a cache-savings
 * callout that ties spend back to the page's cache theme.
 */
const CostPanel = ({ data, periodLabel, savedTokens }: CostPanelProps) => {
  const theme = useTheme();
  const savedUsd = data.estimatedSavingsUsd ?? '$840';
  return (
    <Paper variant="outlined" sx={{ p: '22px 24px', display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Box sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 600, fontSize: 16, mb: 0.75 }}>Cost</Box>

      <Label>Spend · last {periodLabel}</Label>
      <Box
        sx={{
          fontFamily: "'Sora', sans-serif",
          fontWeight: 800,
          fontSize: 46,
          letterSpacing: '-1.8px',
          lineHeight: 1,
          mt: 1.25,
          mb: 1.5,
          backgroundImage: 'linear-gradient(120deg,#8b5cff,#ff6ad5)',
          WebkitBackgroundClip: 'text',
          backgroundClip: 'text',
          color: 'transparent',
        }}
      >
        {data.spend}
      </Box>
      <Stack direction="row" sx={{ alignItems: 'center', gap: 1, fontSize: 12.5, color: 'text.secondary', flexWrap: 'wrap' }}>
        <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.375, fontWeight: 600, color: theme.palette.error.main }}>
          <ArrowUp size={14} />
          {data.deltaPct}
        </Box>
        vs. prev {periodLabel} · tracks the token surge
      </Stack>

      <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 3.75, mt: 2.75 }}>
        <Box>
          <Label>Burn rate</Label>
          <Box sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 700, fontSize: 19, mt: 0.75, whiteSpace: 'nowrap' }}>
            {data.burnRate}
            <Box component="span" sx={{ fontSize: 12, color: 'text.secondary', fontWeight: 600 }}>/h</Box>
          </Box>
        </Box>
        <Box>
          <Label>Projected · 30d</Label>
          <Box sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 700, fontSize: 19, mt: 0.75, whiteSpace: 'nowrap' }}>{data.projected30d}</Box>
        </Box>
        <Box>
          <Label>Cost / 1K tok</Label>
          <Box sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 700, fontSize: 19, mt: 0.75, whiteSpace: 'nowrap' }}>{data.costPer1k}</Box>
        </Box>
      </Stack>

      <Box sx={{ mt: 2.5 }}>
        <CostSpark values={data.trend} />
      </Box>

      <Box sx={{ mt: 'auto', pt: 2.25, borderTop: '1px solid', borderColor: 'divider', fontSize: 12.5, color: 'text.secondary', lineHeight: 1.5 }}>
        Cache reads avoided rebuilding {savedTokens} tokens this window — roughly{' '}
        <Box component="b" sx={{ color: theme.palette.success.main, fontWeight: 700 }}>{savedUsd} saved</Box>{' '}
        vs. uncached prompting.
      </Box>
    </Paper>
  );
};

export default CostPanel;
