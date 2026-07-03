import { alpha, Box, Paper, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { auroraColors, gradients } from '../../../../theme/colors';
import type { MetricSeries } from '../metricsSampleData';
import { fontFamilies } from '../../../../theme/typography';

export interface MetricHeaderProps {
  metric: MetricSeries;
}

const TYPE_BADGE = (type: string) => type.charAt(0).toUpperCase() + type.slice(1);

const ArrowUp = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={13} height={13}>
    <path d="M7 14l5-5 5 5" />
  </svg>
);
const ArrowDown = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={13} height={13}>
    <path d="M7 10l5 5 5-5" />
  </svg>
);

const Stat = ({ label, children, mid = false }: { label: string; children: ReactNode; mid?: boolean }) => (
  <Box>
    <Box sx={{ fontSize: 11, fontWeight: 600, letterSpacing: 0.5, textTransform: 'uppercase', color: 'text.disabled' }}>
      {label}
    </Box>
    <Box
      sx={{
        fontFamily: fontFamilies.display,
        fontWeight: 800,
        fontSize: mid ? 20 : 30,
        letterSpacing: '-0.8px',
        mt: mid ? '11px' : '6px',
        color: 'text.primary',
      }}
    >
      {children}
    </Box>
  </Box>
);

/**
 * Detail-pane header for the selected metric: fully-qualified name, type + unit
 * badges, a one-line description, and the headline stats (sum, rate, peak, delta).
 */
const MetricHeader = ({ metric }: MetricHeaderProps) => {
  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.375, flexWrap: 'wrap' }}>
        <Typography sx={{ fontFamily: fontFamilies.body, fontSize: 19, fontWeight: 700, letterSpacing: '-0.3px' }}>
          {metric.name}
        </Typography>
        <Box
          sx={(t) => ({
            typography: 'eyebrowSm',
            px: 0.75,
            py: '2px',
            borderRadius: '5px',
            color: t.palette.info?.main ?? t.palette.primary.main,
            bgcolor: alpha(t.palette.info?.main ?? auroraColors.cyan, 0.18),
          })}
        >
          {TYPE_BADGE(metric.type)}
        </Box>
        <Box
          sx={{
            typography: 'eyebrowSm',
            px: 0.75,
            py: '2px',
            borderRadius: '5px',
            color: 'text.secondary',
            bgcolor: 'action.hover',
          }}
        >
          {metric.unit}
        </Box>
      </Box>

      <Typography variant="body2" color="text.secondary" sx={{ mt: 1.25, maxWidth: '72ch', lineHeight: 1.5 }}>
        {metric.description}
      </Typography>

      <Box sx={{ display: 'flex', gap: { xs: 3, sm: 4.25 }, flexWrap: 'wrap', mt: 2.25 }}>
        <Stat label={metric.sumLabel}>
          <Box
            component="span"
            sx={{
              backgroundImage: gradients.auroraActionSoft,
              WebkitBackgroundClip: 'text',
              backgroundClip: 'text',
              color: 'transparent',
            }}
          >
            {metric.sum}
          </Box>
        </Stat>
        <Stat label="Rate" mid>
          {metric.rate}
          <Box component="span" sx={{ fontSize: 14, color: 'text.secondary', fontWeight: 600 }}>{metric.rateUnit}</Box>
        </Stat>
        <Stat label="Peak / h" mid>{metric.peak}</Stat>
        <Stat label="vs. prev 24h" mid>
          <Box
            component="span"
            sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, fontSize: 18, color: 'text.secondary', fontWeight: 600 }}
          >
            <Box component="span" sx={{ color: 'text.primary', fontWeight: 700 }}>{metric.delta}</Box>
            {metric.dir === 'down' ? <ArrowDown /> : <ArrowUp />}
          </Box>
        </Stat>
      </Box>
    </Paper>
  );
};

export default MetricHeader;
