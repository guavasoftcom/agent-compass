import { useMemo, useState } from 'react';
import { Box, Typography, useTheme } from '@mui/material';
import StorageRoundedIcon from '@mui/icons-material/StorageRounded';
import {
  SEVERITIES,
  type HistogramBucket,
  type LogHistogram,
  type Severity,
} from '../../logsApi';
import { fontFamilies } from '../../../../theme/typography';
import { severityColor } from '../severity';
import {
  MS_PER_MINUTE,
  MS_PER_HOUR,
  MS_PER_DAY,
} from '../../../../lib/constants';
import { bucketTotal } from './bucketTotal';
import SeverityLegend from './SeverityLegend';
import HistogramTooltip from './HistogramTooltip';

// LogHistogramChart — server-aggregated log-volume histogram. This file owns the
// stacked bars + axis and composes three extracted pieces:
//
//   ┌─ header ─────────────────────────────────────────────────────────┐
//   │ LOG VOLUME · 24h  · 1h buckets  [▤ server-aggregated]  <SeverityLegend>
//   ├───────────────────────────────────────────────────────────────────┤
//   │  ▁▃▅█▇▅▃▁▂   ← stacked severity bars (inline; hover → tooltip)      │
//   ├───────────────────────────────────────────────────────────────────┤
//   │  9AM     12PM     3PM     6PM     ← axisTicks (inline)              │
//   └───────────────────────────────────────────────────────────────────┘
//          hover a bar ──▶ <HistogramTooltip/>  (fixed-position card)
//
// Flow: a bar's onMouseEnter sets { x, y, bucketIndex }; HistogramTooltip reads
// the hovered bucket. SeverityLegend chips call onToggleSeverity, which re-filters
// the bars — muted severities drop out of bucketTotal and the stacks. bucketTotal
// is the shared severity-sum helper used by the bars, maxTotal, and the tooltip.

const formatGranularity = (milliseconds: number): string => {
  if (milliseconds < MS_PER_HOUR) {
    return `${milliseconds / MS_PER_MINUTE} min`;
  }
  if (milliseconds < MS_PER_DAY) {
    return `${milliseconds / MS_PER_HOUR} hr`;
  }
  return `${milliseconds / MS_PER_DAY} day`;
};

interface Props {
  histogram: LogHistogram | undefined;
  hiddenSeverities: Set<Severity>;
  facetSeverity: { value: string; count: number }[];
  windowLabel: string;
  onToggleSeverity: (severity: Severity) => void;
  /** Click a bar to drill the time window into that bucket's range. */
  onBarClick?: (bucket: HistogramBucket) => void;
}

interface TooltipState {
  x: number;
  y: number;
  bucketIndex: number;
}

const LogHistogramChart = ({
  histogram,
  hiddenSeverities,
  facetSeverity,
  windowLabel,
  onToggleSeverity,
  onBarClick,
}: Props) => {
  const theme = useTheme();
  const [tooltip, setTooltip] = useState<TooltipState | null>(null);

  const buckets = histogram?.buckets ?? [];
  const bucketWidthMs = histogram?.bucketMs ?? MS_PER_HOUR;

  const maxTotal = useMemo(() => {
    let runningMax = 1;
    buckets.forEach((bucket) => {
      const total = bucketTotal(bucket, hiddenSeverities);
      if (total > runningMax) {
        runningMax = total;
      }
    });
    return runningMax;
  }, [buckets, hiddenSeverities]);

  const useDateAxisLabels = bucketWidthMs >= 6 * MS_PER_HOUR;
  const axisTicks = useMemo(() => {
    if (!buckets.length) {
      return [] as string[];
    }
    const step = Math.max(1, Math.round(buckets.length / 6));
    const tickLabels: string[] = [];
    for (let i = 0; i < buckets.length; i += step) {
      const bucketStartDate = new Date(buckets[i].t0);
      tickLabels.push(
        useDateAxisLabels
          ? bucketStartDate.toLocaleDateString('en-US', {
              month: 'short',
              day: 'numeric',
            })
          : bucketStartDate
              .toLocaleTimeString('en-US', { hour: 'numeric', hour12: true })
              .replace(' ', ''),
      );
    }
    return tickLabels;
  }, [buckets, useDateAxisLabels]);

  const tooltipBucket = tooltip ? buckets[tooltip.bucketIndex] : null;

  return (
    <Box sx={{ position: 'relative' }}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 2,
          mb: 1.25,
          flexWrap: 'wrap',
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.1 }}>
          <Typography
            component="span"
            sx={{
              fontFamily: fontFamilies.display,
              fontSize: 11.5,
              fontWeight: 700,
              letterSpacing: '0.6px',
              textTransform: 'uppercase',
              color: 'text.secondary',
            }}
          >
            {`Log volume · ${windowLabel}`}
          </Typography>
          {histogram ? (
            <Typography
              component="span"
              sx={{ fontSize: 11, color: 'text.disabled' }}
            >
              {`· ${formatGranularity(bucketWidthMs)} buckets`}
            </Typography>
          ) : null}
          <Box
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 0.6,
              height: 18,
              px: 0.9,
              borderRadius: 0.75,
              bgcolor: 'action.hover',
              color: 'text.disabled',
              fontSize: 10,
              fontWeight: 600,
            }}
            title="Bucketed server-side over the full window — independent of how many rows are loaded"
          >
            <StorageRoundedIcon sx={{ fontSize: 12 }} />
            server-aggregated
          </Box>
        </Box>
        <SeverityLegend
          facetSeverity={facetSeverity}
          hiddenSeverities={hiddenSeverities}
          onToggleSeverity={onToggleSeverity}
        />
      </Box>

      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-end',
          gap: '2px',
          height: 84,
          opacity: histogram ? 1 : 0.4,
          transition: 'opacity .15s',
        }}
        onMouseLeave={() => setTooltip(null)}
      >
        {buckets.map((bucket, i) => {
          const total = bucketTotal(bucket, hiddenSeverities);
          const heightPercent = Math.max(
            total ? 4 : 2,
            (total / maxTotal) * 100,
          );
          return (
            <Box
              key={bucket.t0}
              onMouseEnter={(e) => {
                const boundingRect = (
                  e.currentTarget as HTMLElement
                ).getBoundingClientRect();
                setTooltip({
                  x: boundingRect.left + boundingRect.width / 2,
                  y: boundingRect.top,
                  bucketIndex: i,
                });
              }}
              onClick={() => onBarClick?.(bucket)}
              sx={{
                flex: 1,
                height: `${heightPercent}%`,
                display: 'flex',
                flexDirection: 'column-reverse',
                borderRadius: '3px 3px 0 0',
                overflow: 'hidden',
                minHeight: 2,
                cursor: 'pointer',
                opacity: 0.92,
                '&:hover': { opacity: 1 },
              }}
            >
              {SEVERITIES.filter(
                (severity) =>
                  !hiddenSeverities.has(severity) && bucket[severity] > 0,
              ).map((severity) => (
                <Box
                  key={severity}
                  sx={{
                    width: '100%',
                    height: `${(bucket[severity] / Math.max(1, total)) * 100}%`,
                    bgcolor: severityColor(theme, severity),
                  }}
                />
              ))}
            </Box>
          );
        })}
      </Box>

      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          mt: 0.9,
          fontSize: 11,
          color: 'text.disabled',
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        {axisTicks.map((tickLabel, i) => (
          <Box component="span" key={`${tickLabel}-${i}`}>
            {tickLabel}
          </Box>
        ))}
      </Box>

      {tooltip && tooltipBucket ? (
        <HistogramTooltip
          bucket={tooltipBucket}
          position={{ x: tooltip.x, y: tooltip.y }}
          bucketWidthMs={bucketWidthMs}
          showZoomHint={Boolean(onBarClick)}
        />
      ) : null}
    </Box>
  );
};

export default LogHistogramChart;
