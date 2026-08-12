import { Box, Paper, Typography, useTheme } from '@mui/material';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import type { PaperProps } from '@mui/material';
import type { Theme } from '@mui/material/styles';
import { auroraColors } from '../../../../theme/colors';
import { fontFamilies } from '../../../../theme/typography';
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
 * The three headline counters, as full cards with a trend. Everything else —
 * including an uncurated metric the series endpoint appends that this code has
 * never seen — falls through to the compact tier below. Deliberately an
 * allow-list rather than a rank: a new metric must never require a layout
 * decision, and an unknown metric is by definition not a headline.
 */
const PRIMARY_METRIC_IDS = ['token', 'cost', 'session'];

// The design's own breakpoints, which don't line up with MUI's sm/md/lg keys
// (620 sits inside sm, 1080 between md and lg), so they're written as raw
// queries rather than snapped to the nearest key.
const PRIMARY_TWO_COLUMNS_ABOVE = '@media (min-width:621px)';
const PRIMARY_THREE_COLUMNS_ABOVE = '@media (min-width:901px)';
const SECONDARY_FOUR_COLUMNS_ABOVE = '@media (min-width:1081px)';

const TrendArrowUp = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={11} height={11}>
    <path d="M7 14l5-5 5 5" />
  </svg>
);

const TrendArrowDown = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={11} height={11}>
    <path d="M7 10l5 5 5-5" />
  </svg>
);

/**
 * Metric picker as a KPI strip (replaces the dropdown/rail), laid out in two
 * tiers by importance: the headline counters as full cards with a sparkline,
 * everything else as compact cards showing value and Δ on one baseline row.
 *
 * Selection, hover, keyboard activation and the accent selected state are
 * identical across both tiers — a compact card is still a picker, not a
 * read-only stat.
 */
const MetricKpiStrip = ({ metrics, selectedId, onSelect }: MetricKpiStripProps) => {
  const theme = useTheme();
  const primaryMetrics = metrics.filter((metric) => PRIMARY_METRIC_IDS.includes(metric.id));
  const secondaryMetrics = metrics.filter((metric) => !PRIMARY_METRIC_IDS.includes(metric.id));

  // Shared across both tiers so a compact card reads as the same control.
  const selectableCardSx = (isSelected: boolean) => ({
    minWidth: 0,
    height: '100%',
    cursor: 'pointer',
    transition: 'transform .14s, border-color .14s, box-shadow .14s',
    borderColor: isSelected ? 'primary.main' : 'divider',
    boxShadow: isSelected ? `0 0 0 1px ${theme.palette.primary.main}` : 'none',
    bgcolor: isSelected ? 'action.selected' : 'background.paper',
    '&:hover': { borderColor: 'primary.main', transform: 'translateY(-1px)' },
  });

  const selectionProps = (metricId: string): Partial<PaperProps> => ({
    role: 'button',
    tabIndex: 0,
    onClick: () => onSelect(metricId),
    onKeyDown: (event: ReactKeyboardEvent<HTMLDivElement>) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        onSelect(metricId);
      }
    },
  });

  const deltaChip = (metric: MetricSeries) => {
    const isTrendingUp = metric.dir !== 'down';
    return (
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
        {isTrendingUp ? <TrendArrowUp /> : <TrendArrowDown />}
      </Box>
    );
  };

  const unitSuffix = (metric: MetricSeries) => (
    <Box component="span" sx={{ fontSize: 11, color: 'text.disabled', fontWeight: 600, ml: '2px' }}>
      {metric.unit.replace(/[{}]/g, '')}
    </Box>
  );

  const typeDot = (metric: MetricSeries, diameter: number) => (
    <Box
      sx={{
        width: diameter,
        height: diameter,
        borderRadius: '50%',
        bgcolor: typeColor(metric.type, theme),
      }}
    />
  );

  const primaryCard = (metric: MetricSeries) => (
    <StatCard
      key={metric.id}
      label={metric.name.replace('claude_code.', '')}
      value={
        <>
          {metric.sum}
          {unitSuffix(metric)}
        </>
      }
      displayFont
      displayFontSize={23}
      labelUppercase={false}
      adornment={typeDot(metric, 7)}
      PaperProps={{
        ...selectionProps(metric.id),
        sx: { p: '13px 14px', ...selectableCardSx(metric.id === selectedId) },
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 1 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <LineSparkline values={metric.trend} height={24} />
        </Box>
        {deltaChip(metric)}
      </Box>
    </StatCard>
  );

  /**
   * Hand-built rather than a denser `StatCard`: the compact card puts the value
   * and Δ on one baseline-aligned row, which `StatCard`'s fixed
   * label → value → children stack can't express.
   */
  const secondaryCard = (metric: MetricSeries) => (
    <Paper
      key={metric.id}
      variant="outlined"
      {...selectionProps(metric.id)}
      sx={{ p: '11px 13px', position: 'relative', ...selectableCardSx(metric.id === selectedId) }}
    >
      <Box sx={{ position: 'absolute', top: 11, right: 11 }}>{typeDot(metric, 6)}</Box>
      <Typography
        sx={{
          fontSize: 11.5,
          fontWeight: 600,
          fontFamily: fontFamilies.body,
          color: 'text.secondary',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          pr: 1.5,
          mb: '7px',
        }}
      >
        {metric.name.replace('claude_code.', '')}
      </Typography>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'baseline',
          justifyContent: 'space-between',
          gap: '9px',
        }}
      >
        <Box
          sx={{
            minWidth: 0,
            fontFamily: fontFamilies.display,
            fontWeight: 800,
            fontSize: 18,
            letterSpacing: '-0.5px',
            lineHeight: 1,
            color: 'text.primary',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {metric.sum}
          {unitSuffix(metric)}
        </Box>
        {deltaChip(metric)}
      </Box>
    </Paper>
  );

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
          gridTemplateColumns: '1fr',
          gap: 1.5,
          mb: 1.5,
          [PRIMARY_TWO_COLUMNS_ABOVE]: { gridTemplateColumns: 'repeat(2, 1fr)' },
          [PRIMARY_THREE_COLUMNS_ABOVE]: { gridTemplateColumns: 'repeat(3, 1fr)' },
        }}
      >
        {primaryMetrics.map((metric) => primaryCard(metric))}
      </Box>

      {secondaryMetrics.length > 0 && (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: 'repeat(2, 1fr)',
            gap: 1.5,
            mb: 2.5,
            [SECONDARY_FOUR_COLUMNS_ABOVE]: { gridTemplateColumns: 'repeat(4, 1fr)' },
          }}
        >
          {secondaryMetrics.map((metric) => secondaryCard(metric))}
        </Box>
      )}
    </Box>
  );
};

export default MetricKpiStrip;
