/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { alpha, Box, Paper, Tooltip, Typography, useTheme } from '@mui/material';
import { neutralColors } from '../../../../theme/colors';
import { colorForIndex, radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import { formatTimestamp } from '../../../../lib/format';
import { DISTRIBUTION_POINT_CAP, type MetricDistribution } from '../../metricsApi';
import type { MetricSeries } from '../metricsSampleData';
import {
  buildScatterPoints,
  buildTimeAxisLabels,
  buildValueScale,
  distributionUnitFor,
  formatAxisValue,
  formatDistributionValue,
  formatPercentileValue,
  scaleKindFor,
  summarizeValues,
  valueToFraction,
  type DistributionUnit,
  type ScatterPoint,
  type ValueScale,
} from './distributionScatter';

export interface MetricDistributionCardProps {
  metric: MetricSeries;
  /** Undefined while loading, or when the metric has no distribution. */
  distribution?: MetricDistribution;
  /** ISO-8601 bounds of the REQUEST window: the payload carries none, and x is real time within it. */
  windowFrom: string;
  windowTo: string;
  isLoading?: boolean;
  errorMessage?: string | null;
  /** Called with the exemplar's trace id when a dot is clicked or activated from the keyboard. */
  onOpenTrace: (traceId: string) => void;
  /**
   * True while an attribute filter is active on the page. The distribution API has no filter, so
   * the scatter is unfiltered; the card says so with a one-line note.
   */
  ignoresAttributeFilter?: boolean;
}

const IGNORES_ATTRIBUTE_FILTER_NOTE = 'Distribution ignores attribute filters.';

const SCATTER_HEIGHT_PX = 260;
/** Space around the plot area inside the SVG, for the y tick labels and the x labels. */
const PLOT_PADDING = { left: 58, right: 18, top: 16, bottom: 30 } as const;
/** Width used until the ResizeObserver reports the real one (and in jsdom, which never does). */
const DEFAULT_PLOT_WIDTH_PX = 900;
const REGULAR_DOT_RADIUS_PX = 3.2;
const REGULAR_DOT_OPACITY = 0.38;
const EXEMPLAR_DOT_SIZE_PX = 14;
/** Same-height body for the loading / error / empty states, so the card does not jump. */
const STATE_BODY_HEIGHT_PX = SCATTER_HEIGHT_PX + 70;

// Line colors for the three percentile references; the chips below reuse them as swatches.
const PERCENTILE_LINES = [
  { key: 'p50', label: 'p50', color: colorForIndex(3) },
  { key: 'p95', label: 'p95', color: colorForIndex(4) },
  { key: 'p99', label: 'p99', color: colorForIndex(1) },
] as const;

/** "1 request", "137 requests", or "latest 2,000 requests" once the server-side cap is reached. */
const describeRequestCount = (requestCount: number): string => {
  const formattedCount = requestCount.toLocaleString('en-US');
  if (requestCount >= DISTRIBUTION_POINT_CAP) {
    return `latest ${formattedCount} requests`;
  }
  return requestCount === 1 ? '1 request' : `${formattedCount} requests`;
};

const CardHeader = ({ name, detail }: { name: string; detail?: string }) => (
  <Box
    sx={{
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center',
      flexWrap: 'wrap',
      gap: 1.25,
      mb: 0.75,
    }}
  >
    <Typography
      component="h3"
      sx={{ fontFamily: fontFamilies.display, fontWeight: 600, fontSize: 16, display: 'flex', flexWrap: 'wrap', alignItems: 'baseline', columnGap: 1.125 }}
    >
      {name}
      <Box component="span" sx={{ fontSize: 12.5, color: 'text.secondary', fontWeight: 400, fontFamily: fontFamilies.body }}>
        per request · distribution over time
      </Box>
    </Typography>
    {detail && (
      <Box sx={{ fontSize: 11.5, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>{detail}</Box>
    )}
  </Box>
);

/** One discreet line under the header, rendered only while an attribute filter is active. */
const IgnoresFilterNote = ({ isShown }: { isShown: boolean }) =>
  isShown ? (
    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
      {IGNORES_ATTRIBUTE_FILTER_NOTE}
    </Typography>
  ) : null;

const StateCard = ({
  name,
  ignoresAttributeFilter,
  children,
}: {
  name: string;
  ignoresAttributeFilter: boolean;
  children: ReactNode;
}) => (
  <Paper variant="outlined" sx={{ p: '20px 24px', minWidth: 0 }}>
    <CardHeader name={name} />
    <IgnoresFilterNote isShown={ignoresAttributeFilter} />
    <Box
      sx={{
        minHeight: STATE_BODY_HEIGHT_PX,
        mt: 1.75,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
      }}
    >
      {children}
    </Box>
  </Paper>
);

const LegendChip = ({ children }: { children: ReactNode }) => (
  <Box
    sx={(t) => ({
      display: 'inline-flex',
      alignItems: 'center',
      gap: '7px',
      height: 30,
      px: '11px',
      borderRadius: radii.xs,
      border: `1px solid ${t.palette.divider}`,
      bgcolor: t.custom?.surfaceMuted,
      fontSize: 12.5,
    })}
  >
    {children}
  </Box>
);

const PercentileChip = ({ label, color, value }: { label: string; color: string; value: string }) => (
  <LegendChip>
    <Box
      component="span"
      aria-hidden
      sx={{ width: 14, height: 0, borderTop: `2px dashed ${color}` }}
    />
    <Box component="span" sx={{ color: 'text.secondary' }}>{label}</Box>
    <Box component="span" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{value}</Box>
  </LegendChip>
);

interface ExemplarDotProps {
  point: ScatterPoint;
  unit: DistributionUnit;
  leftPixels: number;
  topPixels: number;
  onOpenTrace: (traceId: string) => void;
}

/** A larger, ringed dot for a server-chosen exemplar: a real button that opens that request's trace. */
const ExemplarDot = ({ point, unit, leftPixels, topPixels, onOpenTrace }: ExemplarDotProps) => {
  const formattedValue = formatDistributionValue(point.value, unit);
  return (
    <Tooltip
      describeChild
      arrow
      placement="top"
      title={
        <Box>
          <Box sx={{ fontWeight: 700 }}>{formattedValue}</Box>
          <Box sx={{ color: 'text.secondary' }}>{formatTimestamp(point.timestamp)}</Box>
          <Box sx={{ color: 'text.secondary' }}>click to open trace</Box>
        </Box>
      }
    >
      <Box
        component="button"
        type="button"
        aria-label={`Open trace for ${formattedValue} request`}
        onClick={() => onOpenTrace(point.traceId as string)}
        sx={(t) => ({
          position: 'absolute',
          left: leftPixels,
          top: topPixels,
          width: EXEMPLAR_DOT_SIZE_PX,
          height: EXEMPLAR_DOT_SIZE_PX,
          m: 0,
          p: 0,
          appearance: 'none',
          borderRadius: '50%',
          transform: 'translate(-50%, -50%)',
          bgcolor: t.palette.background.paper,
          border: `2.5px solid ${t.palette.text.primary}`,
          boxShadow: `0 0 0 3px ${alpha(t.palette.primary.main, 0.28)}, 0 2px 6px ${alpha(neutralColors.black, 0.32)}`,
          cursor: 'pointer',
          zIndex: 2,
          transition: 'transform .15s, box-shadow .15s',
          '&:hover': {
            transform: 'translate(-50%, -50%) scale(1.4)',
            boxShadow: `0 0 0 5px ${alpha(t.palette.primary.main, 0.34)}, 0 3px 10px ${alpha(neutralColors.black, 0.42)}`,
            zIndex: 3,
          },
          '&:focus-visible': {
            outline: `2px solid ${t.palette.primary.main}`,
            outlineOffset: 3,
            zIndex: 3,
          },
        })}
      />
    </Tooltip>
  );
};

interface ScatterPlotProps {
  scatterPoints: ScatterPoint[];
  scale: ValueScale;
  unit: DistributionUnit;
  percentileValues: Record<(typeof PERCENTILE_LINES)[number]['key'], number | null>;
  windowFrom: string;
  windowTo: string;
  onOpenTrace: (traceId: string) => void;
}

/**
 * The plot: an SVG measured in real pixels (so dots stay round at any card width) with the grid,
 * y tick labels, x labels, dashed percentile lines and one small dot per request, and the
 * exemplar buttons absolutely positioned over it in the same pixel space.
 */
const ScatterPlot = ({
  scatterPoints,
  scale,
  unit,
  percentileValues,
  windowFrom,
  windowTo,
  onOpenTrace,
}: ScatterPlotProps) => {
  const theme = useTheme();
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(DEFAULT_PLOT_WIDTH_PX);

  useEffect(() => {
    const element = containerRef.current;
    if (!element) {
      return;
    }
    const observer = new ResizeObserver((entries) => {
      const measuredWidth = entries[0]?.contentRect.width;
      if (measuredWidth) {
        setWidth(measuredWidth);
      }
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const plotWidth = Math.max(1, width - PLOT_PADDING.left - PLOT_PADDING.right);
  const plotHeight = SCATTER_HEIGHT_PX - PLOT_PADDING.top - PLOT_PADDING.bottom;
  const xPixelsFor = (fractionX: number): number => PLOT_PADDING.left + fractionX * plotWidth;
  const yPixelsFor = (fractionY: number): number => PLOT_PADDING.top + (1 - fractionY) * plotHeight;

  const xLabels = useMemo(
    // The "now" label depends on the current wall-clock time at render.
    // eslint-disable-next-line react-hooks/purity
    () => buildTimeAxisLabels(windowFrom, windowTo, Date.now()),
    [windowFrom, windowTo],
  );

  const regularPoints = useMemo(
    () => scatterPoints.filter((point) => point.traceId === null),
    [scatterPoints],
  );
  const exemplarPoints = useMemo(
    () => scatterPoints.filter((point) => point.traceId !== null),
    [scatterPoints],
  );

  const axisLabelStyle = {
    fill: theme.palette.text.disabled,
    fontSize: 11,
    fontFamily: 'inherit',
  } as const;

  return (
    <Box ref={containerRef} sx={{ position: 'relative', width: '100%', height: SCATTER_HEIGHT_PX }}>
      <svg
        role="img"
        aria-label="Per-request scatter plot"
        width={width}
        height={SCATTER_HEIGHT_PX}
        viewBox={`0 0 ${width} ${SCATTER_HEIGHT_PX}`}
        style={{ display: 'block', overflow: 'visible' }}
      >
        {scale.ticks.map((tickValue) => {
          const tickY = yPixelsFor(valueToFraction(scale, tickValue));
          return (
            <g key={tickValue}>
              <line
                x1={PLOT_PADDING.left}
                x2={width - PLOT_PADDING.right}
                y1={tickY}
                y2={tickY}
                stroke={theme.palette.divider}
                strokeWidth={1}
              />
              <text x={PLOT_PADDING.left - 8} y={tickY + 4} textAnchor="end" {...axisLabelStyle}>
                {formatAxisValue(tickValue, unit, scale.maximum)}
              </text>
            </g>
          );
        })}

        {xLabels.map((label, index) => {
          const isFirst = index === 0;
          const isLast = index === xLabels.length - 1;
          return (
            <text
              key={index}
              x={xPixelsFor(xLabels.length > 1 ? index / (xLabels.length - 1) : 0)}
              y={SCATTER_HEIGHT_PX - 9}
              textAnchor={isFirst ? 'start' : isLast ? 'end' : 'middle'}
              {...axisLabelStyle}
            >
              {label}
            </text>
          );
        })}

        {regularPoints.map((point) => (
          <circle
            key={point.key}
            cx={xPixelsFor(point.fractionX)}
            cy={yPixelsFor(point.fractionY)}
            r={REGULAR_DOT_RADIUS_PX}
            fill={theme.palette.primary.main}
            fillOpacity={REGULAR_DOT_OPACITY}
          />
        ))}

        {PERCENTILE_LINES.map((line) => {
          const percentileValue = percentileValues[line.key];
          if (percentileValue === null) {
            return null;
          }
          const lineY = yPixelsFor(valueToFraction(scale, percentileValue));
          return (
            <line
              key={line.key}
              data-percentile={line.key}
              x1={PLOT_PADDING.left}
              x2={width - PLOT_PADDING.right}
              y1={lineY}
              y2={lineY}
              stroke={line.color}
              strokeWidth={1.6}
              strokeDasharray="5 4"
            />
          );
        })}
      </svg>

      {exemplarPoints.map((point) => (
        <ExemplarDot
          key={point.key}
          point={point}
          unit={unit}
          leftPixels={xPixelsFor(point.fractionX)}
          topPixels={yPixelsFor(point.fractionY)}
          onOpenTrace={onOpenTrace}
        />
      ))}
    </Box>
  );
};

interface DistributionBodyProps {
  metric: MetricSeries;
  distribution: MetricDistribution;
  windowFrom: string;
  windowTo: string;
  onOpenTrace: (traceId: string) => void;
  ignoresAttributeFilter: boolean;
}

/** The populated card: header, scatter, percentile / exemplar chips, and the explanatory copy. */
const DistributionBody = ({
  metric,
  distribution,
  windowFrom,
  windowTo,
  onOpenTrace,
  ignoresAttributeFilter,
}: DistributionBodyProps) => {
  const unit = distributionUnitFor(metric.unit);
  const scaleKind = scaleKindFor(unit);

  const scale = useMemo(
    () =>
      buildValueScale(
        scaleKind,
        distribution.points.map((point) => point.value),
      ),
    [scaleKind, distribution.points],
  );
  const scatterPoints = useMemo(
    () => buildScatterPoints(distribution.points, scale, windowFrom, windowTo),
    [distribution.points, scale, windowFrom, windowTo],
  );
  const summary = useMemo(
    () => summarizeValues(distribution.points.map((point) => point.value)),
    [distribution.points],
  );

  const requestCountLabel = describeRequestCount(distribution.points.length);
  const headerDetail = scaleKind === 'log' ? `${requestCountLabel} · log scale` : requestCountLabel;

  return (
    <Paper variant="outlined" sx={{ p: '20px 24px', minWidth: 0 }}>
      <CardHeader name={metric.name} detail={headerDetail} />
      <IgnoresFilterNote isShown={ignoresAttributeFilter} />

      <Box sx={{ mt: 1.75 }}>
        <ScatterPlot
          scatterPoints={scatterPoints}
          scale={scale}
          unit={unit}
          percentileValues={{ p50: summary.p50, p95: summary.p95, p99: summary.p99 }}
          windowFrom={windowFrom}
          windowTo={windowTo}
          onOpenTrace={onOpenTrace}
        />
      </Box>

      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 1.5,
          mt: 2.25,
        }}
      >
        <Box sx={{ display: 'flex', gap: 1.25, flexWrap: 'wrap' }}>
          {PERCENTILE_LINES.map((line) => (
            <PercentileChip
              key={line.key}
              label={line.label}
              color={line.color}
              value={formatPercentileValue(summary[line.key], unit)}
            />
          ))}
          <LegendChip>
            <Box
              component="span"
              aria-hidden
              sx={(t) => ({
                width: 11,
                height: 11,
                borderRadius: '50%',
                bgcolor: t.palette.background.paper,
                border: `2px solid ${t.palette.text.primary}`,
                boxShadow: `0 0 0 2px ${alpha(t.palette.primary.main, 0.3)}`,
              })}
            />
            <Box component="span" sx={{ color: 'text.secondary' }}>exemplar</Box>
          </LegendChip>
        </Box>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', maxWidth: 420, lineHeight: 1.5 }}>
          Each dot is one real request, plotted at its own time and value — gaps are simply idle
          time, not missing data. Ringed dots are{' '}
          <Box component="b" sx={{ color: 'text.primary' }}>exemplars</Box> — click one to open that
          request&rsquo;s trace. Built from per-request API logs, so totals here will not match the
          counter-based Sum above.
        </Typography>
      </Box>
    </Paper>
  );
};

/**
 * Per-request distribution card for the selected metric: a scatter of every request at its real
 * time and value, dashed p50/p95/p99 reference lines (computed client-side), and clickable
 * ringed exemplar dots that open that request's trace. Presentational — the container fetches
 * and supplies the points and the request window.
 *
 * Never renders nothing: a metric with no per-request value gets a persistent placeholder
 * (so the row does not appear and disappear as the user browses the catalog), and the
 * loading / error / empty states keep the card's shape.
 */
const MetricDistributionCard = ({
  metric,
  distribution,
  windowFrom,
  windowTo,
  isLoading = false,
  errorMessage = null,
  onOpenTrace,
  ignoresAttributeFilter = false,
}: MetricDistributionCardProps) => {
  if (!metric.hasDistribution) {
    return (
      <Paper
        variant="outlined"
        sx={{
          p: '26px 24px',
          minWidth: 0,
          display: 'flex',
          alignItems: 'center',
          gap: 1.75,
          color: 'text.secondary',
          fontSize: 13,
          lineHeight: 1.5,
        }}
      >
        <Box
          component="svg"
          aria-hidden
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={1.8}
          sx={{ width: 28, height: 28, flexShrink: 0, color: 'text.disabled' }}
        >
          <rect x="3" y="3" width="18" height="18" rx="3" />
          <path d="M8 14l2.5-3 3 2.5L18 9" />
        </Box>
        <Box>
          No per-request distribution is available for{' '}
          <Box component="b" sx={{ color: 'text.primary', fontWeight: 600 }}>{metric.name}</Box>.
          Distributions only make sense for metrics with a per-request value (tokens, cost).
        </Box>
      </Paper>
    );
  }

  if (errorMessage) {
    return (
      <StateCard name={metric.name} ignoresAttributeFilter={ignoresAttributeFilter}>
        <Typography sx={{ color: 'error.main' }}>{errorMessage}</Typography>
      </StateCard>
    );
  }

  if (isLoading || !distribution) {
    return (
      <StateCard name={metric.name} ignoresAttributeFilter={ignoresAttributeFilter}>
        <Typography color="text.secondary">Loading distribution…</Typography>
      </StateCard>
    );
  }

  if (distribution.points.length === 0) {
    return (
      <StateCard name={metric.name} ignoresAttributeFilter={ignoresAttributeFilter}>
        <Typography color="text.secondary">No requests in this window</Typography>
      </StateCard>
    );
  }

  return (
    <DistributionBody
      metric={metric}
      distribution={distribution}
      windowFrom={windowFrom}
      windowTo={windowTo}
      onOpenTrace={onOpenTrace}
      ignoresAttributeFilter={ignoresAttributeFilter}
    />
  );
};

export default MetricDistributionCard;
