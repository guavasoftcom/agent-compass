import { Box, LinearProgress, Paper, Typography, useTheme } from '@mui/material';
import type { ReactNode } from 'react';
import { colorForIndex } from '../../../theme';
import type { MetricSeries } from './metricsSampleData';

export interface MetricBreakdownProps {
  metric: MetricSeries;
  /** Active split key ("None" or one of metric.splits). */
  split: string;
}

const SummaryStat = ({ label, children }: { label: string; children: ReactNode }) => (
  <Box>
    <Box sx={{ fontSize: 11, fontWeight: 600, letterSpacing: 0.5, textTransform: 'uppercase', color: 'text.disabled' }}>
      {label}
    </Box>
    <Box sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 700, fontSize: 20, mt: 0.5, letterSpacing: '-0.3px' }}>
      {children}
    </Box>
  </Box>
);

/**
 * Right-hand card in the detail pane. With no split it shows a compact summary
 * (sum / rate / peak); with a split active it lists the per-attribute shares as
 * labelled bars. Matches the chart's split state.
 */
const MetricBreakdown = ({ metric, split }: MetricBreakdownProps) => {
  const theme = useTheme();
  const rows = metric.splits[split];
  const hasSplits = Object.keys(metric.splits).length > 0;
  const trackColor = theme.custom?.progressTrack ?? theme.palette.action.hover;

  if (split === 'None' || !rows) {
    return (
      <Paper variant="outlined" sx={{ p: '20px 22px', height: '100%', display: 'flex', flexDirection: 'column' }}>
        <Typography sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 600, fontSize: 15 }}>Summary</Typography>
        <Typography variant="caption" color="text.secondary" sx={{ mb: 1 }}>this window</Typography>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 0.5 }}>
          <SummaryStat label={metric.sumLabel}>{metric.sum}</SummaryStat>
          <SummaryStat label="Rate">
            {metric.rate}
            <Box component="span" sx={{ fontSize: 12, color: 'text.secondary', fontWeight: 600 }}>{metric.rateUnit}</Box>
          </SummaryStat>
          <SummaryStat label="Peak / h">{metric.peak}</SummaryStat>
        </Box>
        <Box sx={{ mt: 'auto', pt: 2, borderTop: '1px solid', borderColor: 'divider', fontSize: 12, color: 'text.secondary', lineHeight: 1.5 }}>
          {hasSplits
            ? 'Use Split by above to break this metric down by attribute.'
            : 'This metric has no attribute breakdown — it’s a single series.'}
        </Box>
      </Paper>
    );
  }

  return (
    <Paper variant="outlined" sx={{ p: '20px 22px', height: '100%' }}>
      <Typography sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 600, fontSize: 15 }}>
        By {split.toLowerCase()}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        share of {metric.sum} · this window
      </Typography>
      {rows.map((row) => {
        const color = colorForIndex(row.colorIndex);
        return (
          <Box key={row.label} sx={{ mt: 1.875 }}>
            <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1, mb: 0.75, fontSize: 13 }}>
              <Box sx={{ width: 9, height: 9, borderRadius: '3px', flexShrink: 0, alignSelf: 'center', bgcolor: color }} />
              <Box sx={{ fontWeight: 600, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {row.label}
              </Box>
              <Box sx={{ color: 'text.secondary', fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                <Box component="b" sx={{ color: 'text.primary', fontWeight: 700 }}>{row.value}</Box> · {row.pct}%
              </Box>
            </Box>
            <LinearProgress
              variant="determinate"
              value={row.pct}
              sx={{ height: 8, borderRadius: 5, bgcolor: trackColor, '& .MuiLinearProgress-bar': { backgroundColor: color, borderRadius: 5 } }}
            />
          </Box>
        );
      })}
    </Paper>
  );
};

export default MetricBreakdown;
