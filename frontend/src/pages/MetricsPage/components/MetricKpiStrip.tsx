import { Box, Paper, Typography, useTheme } from '@mui/material';
import type { Theme } from '@mui/material/styles';
import type { MetricSeries } from './metricsSampleData';

export interface MetricKpiStripProps {
  metrics: MetricSeries[];
  selectedId: string;
  onSelect: (id: string) => void;
}

const typeColor = (type: MetricSeries['type'], t: Theme): string => {
  if (type === 'gauge') {
    return t.palette.primary.main;
  }
  if (type === 'histogram') {
    return t.palette.secondary?.main ?? '#e84bc0';
  }
  return t.palette.info?.main ?? '#1aa7dd';
};

/** Tiny area sparkline for a KPI card. */
const CardSpark = ({ values }: { values: number[] }) => {
  const theme = useTheme();
  const w = 120;
  const h = 24;
  const max = Math.max(...values);
  const min = Math.min(...values);
  const range = max - min || 1;
  const x = (i: number) => (i * w) / (values.length - 1);
  const y = (v: number) => h - 2 - ((v - min) / range) * (h - 4);
  const line = values.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join('');
  const area = `${line}L${w},${h}L0,${h}Z`;
  return (
    <svg viewBox={`0 0 ${w} ${h}`} width="100%" height={h} preserveAspectRatio="none">
      <path d={area} fill={`${theme.palette.primary.main}1f`} />
      <path d={line} fill="none" stroke={theme.palette.primary.main} strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
    </svg>
  );
};

const ArrowUp = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={11} height={11}>
    <path d="M7 14l5-5 5 5" />
  </svg>
);
const ArrowDown = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={11} height={11}>
    <path d="M7 10l5 5 5-5" />
  </svg>
);

/**
 * Metric picker as a KPI strip (replaces the dropdown/rail): a responsive row of
 * cards, one per metric, each showing name · headline value · sparkline · Δ.
 * Doubles as an at-a-glance overview; click a card to load its detail below.
 * The selected card gets an accent ring.
 */
const MetricKpiStrip = ({ metrics, selectedId, onSelect }: MetricKpiStripProps) => {
  const theme = useTheme();
  return (
    <Box>
      <Typography
        sx={{
          fontFamily: "'Sora', sans-serif",
          fontSize: 10.5,
          fontWeight: 700,
          letterSpacing: 1.1,
          textTransform: 'uppercase',
          color: 'text.disabled',
          mb: 1.25,
        }}
      >
        Metrics · claude_code
      </Typography>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: {
            xs: 'repeat(2, 1fr)',
            sm: 'repeat(3, 1fr)',
            lg: 'repeat(6, 1fr)',
          },
          gap: 1.5,
        }}
      >
        {metrics.map((metric) => {
          const isOn = metric.id === selectedId;
          const up = metric.dir !== 'down';
          return (
            <Paper
              key={metric.id}
              variant="outlined"
              role="button"
              tabIndex={0}
              onClick={() => onSelect(metric.id)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onSelect(metric.id);
                }
              }}
              sx={{
                p: '13px 14px',
                position: 'relative',
                minWidth: 0,
                cursor: 'pointer',
                transition: 'transform .14s, border-color .14s, box-shadow .14s',
                borderColor: isOn ? 'primary.main' : 'divider',
                boxShadow: isOn ? `0 0 0 1px ${theme.palette.primary.main}` : 'none',
                bgcolor: isOn ? 'action.selected' : 'background.paper',
                '&:hover': { borderColor: 'primary.main', transform: 'translateY(-1px)' },
              }}
            >
              <Box
                sx={{ position: 'absolute', top: 13, right: 13, width: 7, height: 7, borderRadius: '50%', bgcolor: typeColor(metric.type, theme) }}
              />
              <Typography
                sx={{
                  fontSize: 12,
                  fontWeight: 600,
                  fontFamily: "'Space Grotesk', sans-serif",
                  color: isOn ? 'text.primary' : 'text.secondary',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  pr: 1.5,
                  mb: 1.125,
                }}
              >
                {metric.name.replace('claude_code.', '')}
              </Typography>
              <Box sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 800, fontSize: 23, letterSpacing: '-0.7px', lineHeight: 1 }}>
                {metric.sum}
                <Box component="span" sx={{ fontSize: 11, color: 'text.disabled', fontWeight: 600, ml: '2px' }}>
                  {metric.unit.replace(/[{}]/g, '')}
                </Box>
              </Box>
              <Box sx={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 1, mt: 1.25 }}>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <CardSpark values={metric.trend} />
                </Box>
                <Box
                  sx={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '2px',
                    fontSize: 11,
                    fontWeight: 700,
                    fontVariantNumeric: 'tabular-nums',
                    flexShrink: 0,
                    color: up ? 'success.main' : 'error.main',
                  }}
                >
                  {metric.delta}
                  {up ? <ArrowUp /> : <ArrowDown />}
                </Box>
              </Box>
            </Paper>
          );
        })}
      </Box>
    </Box>
  );
};

export default MetricKpiStrip;
