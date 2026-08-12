import { Box, Typography, useTheme } from '@mui/material';
import type { Theme } from '@mui/material/styles';
import { auroraColors } from '../../../../theme/colors';
import type { MetricSeries } from '../metricsSampleData';
import StatCard from '../../../../components/StatCard/StatCard';
import LineSparkline from '../../../../components/LineSparkline';

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
    return t.palette.secondary?.main ?? auroraColors.pink;
  }
  return t.palette.info?.main ?? auroraColors.cyan;
};

/**
 * Widest single row the strip will lay out on a large screen. Beyond this the
 * cards wrap to a second row rather than getting thinner — at 7 columns a card
 * is already ~155px at the lg breakpoint, which is about as narrow as the
 * headline value and its unit suffix stay readable.
 */
const LARGE_SCREEN_MAX_COLUMNS = 7;

/**
 * Metric picker as a KPI strip (replaces the dropdown/rail): a responsive row of
 * cards, one per metric, each showing name · headline value · sparkline · Δ.
 * Doubles as an at-a-glance overview; click a card to load its detail below.
 * The selected card gets an accent ring.
 *
 * The column count follows `metrics.length` rather than being fixed, because the
 * series endpoint appends a card for any uncurated metric it finds in the
 * database — the strip has to absorb a metric this code has never seen.
 */
const MetricKpiStrip = ({ metrics, selectedId, onSelect }: MetricKpiStripProps) => {
  const theme = useTheme();
  const columnCount = Math.min(Math.max(metrics.length, 1), LARGE_SCREEN_MAX_COLUMNS);
  return (
    <Box>
      <Typography
        sx={{
          typography: 'eyebrowSm',
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
            lg: `repeat(${columnCount}, 1fr)`,
          },
          gap: 1.5,
        }}
      >
        {metrics.map((metric) => {
          const isSelected = metric.id === selectedId;
          const isTrendingUp = metric.dir !== 'down';
          const typeDot = (
            <Box
              sx={{ width: 7, height: 7, borderRadius: '50%', bgcolor: typeColor(metric.type, theme) }}
            />
          );
          const sparklineAndTrend = (
            <Box sx={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 1 }}>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <LineSparkline values={metric.trend} height={24} />
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
                  color: isTrendingUp ? 'success.main' : 'error.main',
                }}
              >
                {metric.delta}
                {isTrendingUp ? (
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={3}
                    width={11}
                    height={11}
                  >
                    <path d="M7 14l5-5 5 5" />
                  </svg>
                ) : (
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={3}
                    width={11}
                    height={11}
                  >
                    <path d="M7 10l5 5 5-5" />
                  </svg>
                )}
              </Box>
            </Box>
          );
          const metricValue = (
            <>
              {metric.sum}
              <Box component="span" sx={{ fontSize: 11, color: 'text.disabled', fontWeight: 600, ml: '2px' }}>
                {metric.unit.replace(/[{}]/g, '')}
              </Box>
            </>
          );
          return (
            <StatCard
              key={metric.id}
              label={metric.name.replace('claude_code.', '')}
              value={metricValue}
              displayFont
              displayFontSize={23}
              labelUppercase={false}
              adornment={typeDot}
              PaperProps={{
                role: 'button',
                tabIndex: 0,
                onClick: () => onSelect(metric.id),
                onKeyDown: (event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    onSelect(metric.id);
                  }
                },
                sx: {
                  p: '13px 14px',
                  minWidth: 0,
                  cursor: 'pointer',
                  transition: 'transform .14s, border-color .14s, box-shadow .14s',
                  borderColor: isSelected ? 'primary.main' : 'divider',
                  boxShadow: isSelected ? `0 0 0 1px ${theme.palette.primary.main}` : 'none',
                  bgcolor: isSelected ? 'action.selected' : 'background.paper',
                  '&:hover': { borderColor: 'primary.main', transform: 'translateY(-1px)' },
                },
              }}
            >
              {sparklineAndTrend}
            </StatCard>
          );
        })}
      </Box>
    </Box>
  );
};

export default MetricKpiStrip;
