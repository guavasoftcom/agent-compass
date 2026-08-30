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
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { alpha, Box, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import { neutralColors } from '../../theme/colors';
import { fontFamilies } from '../../theme/typography';
import { radii } from '../../theme/theme';
import {
  PLOT_PADDING,
  buildCoordinateFns,
  buildLayersAndYDomain,
  buildPathStrings,
} from './areaTrendGeometry';

export interface AreaTrendSeries {
  label: string;
  data: number[];
  color: string;
}

export interface AreaTrendChartProps {
  axisDates: Date[];
  series: AreaTrendSeries[];
  /** Caption rotated along the y-axis (e.g. "calls", "tokens"). */
  yLabel: string;
  /** Format a y-axis tick value (default: rounded integer). */
  formatY?: (value: number) => string;
  /** Format an x-axis tick date (default: localized hour). */
  formatX?: (date: Date) => string;
  /** Format the hover-tooltip header date (default: localized date + time). */
  formatTooltipHeader?: (date: Date) => string;
  height?: number;
  /** One flag per series; hidden series drop out of the stack. Default: all shown. */
  activeStates?: boolean[];
  /** Label of the series to spotlight (others dim). Default: none. */
  focusedLabel?: string | null;
  /**
   * Stack series into a cumulative area (default), or draw each series as its own
   * line from a shared baseline. Unstacked is required when series span very
   * different magnitudes (e.g. token types) so the small ones don't vanish.
   */
  stacked?: boolean;
  /**
   * Y-axis scale. 'log' keeps series that span orders of magnitude all visible:
   * the floor sits a decade below the smallest positive value, ticks step by
   * powers of ten, and idle/zero values clamp to the floor. Default 'linear'.
   */
  yScale?: 'linear' | 'log';
  /**
   * Render sparse whole-number counters as bars instead of an area.
   * When true, y-axis ticks step by whole numbers and the series renders
   * as stacked or grouped bars with a baseline rule.
   */
  isDiscrete?: boolean;
}

const MAXIMUM_BAR_WIDTH = 24;
const BAR_WIDTH_FRACTION_OF_BUCKET = 0.7;
/** Below this a bar reads as a flat sliver, so an empty bucket draws nothing. */
const MINIMUM_VISIBLE_BAR_HEIGHT = 0.2;
const BAR_CORNER_RADIUS = 2.5;
const BAR_BASELINE_OPACITY = 0.28;

const defaultFormatX = (date: Date): string =>
  date.toLocaleTimeString([], { hour: 'numeric' });

const formatXDate = (date: Date): string =>
  date.toLocaleDateString([], { month: 'short', day: 'numeric' });

const defaultTooltipHeader = (date: Date): string =>
  date.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });

/**
 * Aurora trend chart — hand-built SVG (no @mui/x-charts) so the gradient fade, crisp
 * series lines, dashed grid, and muted axis labels match the design preview exactly.
 * Width is measured so text stays undistorted at any size.
 *
 * Two layout modes:
 *  - **stacked + linear** (default): cumulative bands, the first series at the bottom —
 *    used by "Calls over time".
 *  - **unstacked + log** (`stacked={false} yScale="log"`): each series is its own line
 *    from a shared floor; the log axis keeps series spanning orders of magnitude all
 *    visible — used by "Token usage over time".
 *
 * Interactive: a hover crosshair drops a ranked tooltip (every visible series at that
 * bucket, high→low; the Total row is shown only when stacked). Pass `activeStates` to
 * drop hidden series and `focusedLabel` to spotlight one — driven by the companion
 * `AreaTrendLegend` + `useSeriesVisibility`.
 */
const AreaTrendChart = ({
  axisDates,
  series,
  yLabel,
  formatY = (value) => String(Math.round(value)),
  formatX = defaultFormatX,
  formatTooltipHeader = defaultTooltipHeader,
  height = 300,
  activeStates,
  focusedLabel = null,
  stacked = true,
  yScale = 'linear',
  isDiscrete = false,
}: AreaTrendChartProps) => {
  const theme = useTheme();
  const gradientId = useId().replace(/:/g, '');
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(800);
  const [hover, setHover] = useState<{
    index: number;
    leftPixels: number;
  } | null>(null);

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

  const isLogarithmic = yScale === 'log';
  const bucketCount = axisDates.length;
  const plotWidth = width - PLOT_PADDING.left - PLOT_PADDING.right;
  const plotHeight = height - PLOT_PADDING.top - PLOT_PADDING.bottom;
  const baselineY = PLOT_PADDING.top + plotHeight;

  // Layers + y-domain: rebuilding these means re-scanning every series/bucket,
  // so they're memoized on the props that can actually change their shape.
  // Deliberately excludes `hover` — the crosshair/tooltip must never invalidate
  // this (see handleMove below, which fires on every mouse-move pixel). The
  // actual computation is a pure function (`areaTrendGeometry.ts`) so it can be
  // unit-tested without a DOM renderer.
  const { layers, yFloor, yCeiling, yTicks } = useMemo(
    () => buildLayersAndYDomain(series, activeStates, bucketCount, stacked, isLogarithmic, isDiscrete),
    [series, activeStates, bucketCount, stacked, isLogarithmic, isDiscrete],
  );

  // Coordinate mappers: memoized separately from layers/y-domain so a resize
  // (width/height change) doesn't force a full layer rebuild, and so the path-
  // string memo below can depend on a stable function reference instead of
  // re-deriving exhaustive-deps against every primitive it closes over.
  const coordinateFns = useMemo(
    () => buildCoordinateFns(bucketCount, plotWidth, plotHeight, isLogarithmic, yFloor, yCeiling),
    [bucketCount, plotWidth, plotHeight, isLogarithmic, yFloor, yCeiling],
  );
  const { xCoordinateAt, yCoordinateAt } = coordinateFns;

  // Band/line path strings: the expensive part `handleMove` was rebuilding on
  // every pixel of mouse movement (points × layers × 4 per hover tick). Memoized
  // on `layers` + `coordinateFns` only, so hover never invalidates this.
  const { bandPaths, linePaths } = useMemo(
    () => buildPathStrings(layers, coordinateFns),
    [layers, coordinateFns],
  );

  const xTickCount = Math.min(8, bucketCount);
  const xTickIndexes =
    bucketCount === 0
      ? []
      : bucketCount === 1
        ? [0]
        : Array.from({ length: xTickCount }, (_, i) =>
            Math.round((i * (bucketCount - 1)) / (xTickCount - 1)),
          );

  const gridColor = theme.palette.divider;
  const labelColor = theme.palette.text.secondary;

  // Two-line date+time labels once the window spans more than 2h; a single time
  // line below that. PLOT_PADDING.bottom (44) reserves room for the second line.
  const spanMilliseconds =
    bucketCount >= 2
      ? axisDates[bucketCount - 1].getTime() - axisDates[0].getTime()
      : 0;
  const twoLine = spanMilliseconds > 2 * 60 * 60 * 1000;

  // Line opacity reflects focus; bands additionally fade when unstacked so the
  // overlapping fills stay legible.
  const lineOpacityFor = (label: string): number =>
    !focusedLabel || focusedLabel === label ? 1 : 0.12;
  const bandOpacityFor = (label: string): number =>
    lineOpacityFor(label) * (stacked ? 1 : 0.35);

  // Discrete bars: capped so a short window doesn't produce slabs, and kept off
  // each other by taking only 70% of a bucket's width.
  const barWidth = Math.min(
    MAXIMUM_BAR_WIDTH,
    (plotWidth / Math.max(1, bucketCount)) * BAR_WIDTH_FRACTION_OF_BUCKET,
  );

  const handleMove = (event: ReactMouseEvent<SVGSVGElement>) => {
    if (bucketCount < 1) {
      return;
    }
    const rect = event.currentTarget.getBoundingClientRect();
    const localX = ((event.clientX - rect.left) / rect.width) * width;
    const rawIndex =
      bucketCount <= 1
        ? 0
        : Math.round(
            ((localX - PLOT_PADDING.left) * (bucketCount - 1)) / plotWidth,
          );
    const index = Math.max(0, Math.min(bucketCount - 1, rawIndex));
    setHover({ index, leftPixels: event.clientX - rect.left });
  };

  // Tooltip rows: every visible series at the hovered bucket, ranked high→low.
  const hoverRows =
    hover != null
      ? layers
          .map((layer) => ({
            label: layer.label,
            color: layer.color,
            value: series[layer.seriesIndex].data[hover.index] ?? 0,
          }))
          .sort((a, b) => b.value - a.value)
      : [];
  const hoverTotal = hoverRows.reduce((sum, row) => sum + row.value, 0);

  // Flip the tooltip to the left of the cursor when near the right edge.
  const TOOLTIP_WIDTH = 190;
  const tooltipLeft =
    hover != null
      ? Math.max(
          4,
          hover.leftPixels > width - TOOLTIP_WIDTH
            ? hover.leftPixels - TOOLTIP_WIDTH
            : hover.leftPixels + 16,
        )
      : 0;

  return (
    <Box ref={containerRef} sx={{ width: '100%', position: 'relative' }}>
      <svg
        width={width}
        height={height}
        role="img"
        aria-label={`${yLabel} over time`}
        onMouseMove={handleMove}
        onMouseLeave={() => setHover(null)}
        style={{ display: 'block', overflow: 'visible' }}
      >
        <defs>
          {layers.map((layer) => (
            <linearGradient
              key={layer.seriesIndex}
              id={`${gradientId}-${layer.seriesIndex}`}
              x1="0"
              y1="0"
              x2="0"
              y2="1"
            >
              {/* Bars carry the series color as a fill in their own right, so they
                  hold far more of it than an area band fading into the plot. */}
              <stop offset="0" stopColor={layer.color} stopOpacity={isDiscrete ? 0.95 : 0.4} />
              <stop offset="1" stopColor={layer.color} stopOpacity={isDiscrete ? 0.45 : 0.04} />
            </linearGradient>
          ))}
        </defs>

        {yTicks.map((tickValue, i) => (
          <g key={i}>
            <line
              x1={PLOT_PADDING.left}
              y1={yCoordinateAt(tickValue)}
              x2={width - PLOT_PADDING.right}
              y2={yCoordinateAt(tickValue)}
              stroke={gridColor}
              strokeDasharray="3 4"
            />
            <text
              x={PLOT_PADDING.left - 8}
              y={yCoordinateAt(tickValue) + 4}
              textAnchor="end"
              fill={labelColor}
              fontSize={11}
              fontFamily={theme.typography.fontFamily}
            >
              {formatY(tickValue)}
            </text>
          </g>
        ))}

        {isDiscrete ? (
          <>
            {layers.map((layer) => (
              <g
                key={`bars-${layer.seriesIndex}`}
                opacity={bandOpacityFor(layer.label)}
                style={{ transition: 'opacity .12s' }}
              >
                {layer.upper.map((value, bucketIndex) => {
                  const barTopY = yCoordinateAt(value);
                  const barHeight = yCoordinateAt(layer.lower[bucketIndex] ?? 0) - barTopY;
                  // An empty bucket draws nothing — absence is absence, not a
                  // flat sliver sitting on the baseline.
                  if (barHeight <= MINIMUM_VISIBLE_BAR_HEIGHT) {
                    return null;
                  }
                  return (
                    <rect
                      key={bucketIndex}
                      x={xCoordinateAt(bucketIndex) - barWidth / 2}
                      y={barTopY}
                      width={barWidth}
                      height={barHeight}
                      rx={BAR_CORNER_RADIUS}
                      fill={`url(#${gradientId}-${layer.seriesIndex})`}
                    />
                  );
                })}
              </g>
            ))}
            <line
              x1={PLOT_PADDING.left}
              y1={baselineY}
              x2={width - PLOT_PADDING.right}
              y2={baselineY}
              stroke={layers[0]?.color ?? gridColor}
              strokeOpacity={BAR_BASELINE_OPACITY}
              strokeWidth={1.5}
              vectorEffect="non-scaling-stroke"
            />
          </>
        ) : (
          <>
            {layers.map((layer, layerIndex) => (
              <path
                key={`band-${layer.seriesIndex}`}
                d={bandPaths[layerIndex]}
                fill={`url(#${gradientId}-${layer.seriesIndex})`}
                opacity={bandOpacityFor(layer.label)}
                style={{ transition: 'opacity .12s' }}
              />
            ))}

            {layers.map((layer, layerIndex) => (
              <path
                key={`line-${layer.seriesIndex}`}
                d={linePaths[layerIndex]}
                fill="none"
                stroke={layer.color}
                strokeWidth={2.5}
                strokeLinejoin="round"
                opacity={lineOpacityFor(layer.label)}
                style={{ transition: 'opacity .12s' }}
              />
            ))}
          </>
        )}

        {xTickIndexes.map((tickIndex) => {
          const date = axisDates[tickIndex];
          if (twoLine) {
            return (
              <text
                key={tickIndex}
                x={xCoordinateAt(tickIndex)}
                y={height - 26}
                textAnchor="middle"
                fill={labelColor}
                fontSize={11}
                fontFamily={theme.typography.fontFamily}
              >
                <tspan x={xCoordinateAt(tickIndex)} dy="0">
                  {formatXDate(date)}
                </tspan>
                <tspan x={xCoordinateAt(tickIndex)} dy="14">
                  {formatX(date)}
                </tspan>
              </text>
            );
          }
          return (
            <text
              key={tickIndex}
              x={xCoordinateAt(tickIndex)}
              y={height - 14}
              textAnchor="middle"
              fill={labelColor}
              fontSize={11}
              fontFamily={theme.typography.fontFamily}
            >
              {formatX(date)}
            </text>
          );
        })}

        <text
          x={12}
          y={PLOT_PADDING.top + plotHeight / 2}
          textAnchor="middle"
          fill={labelColor}
          fontSize={11}
          fontFamily={theme.typography.fontFamily}
          transform={`rotate(-90 12 ${PLOT_PADDING.top + plotHeight / 2})`}
        >
          {yLabel}
        </text>

        {/* Hover crosshair: vertical guide + a dot on each visible line */}
        {hover != null && (
          <g pointerEvents="none">
            <line
              x1={xCoordinateAt(hover.index)}
              y1={PLOT_PADDING.top}
              x2={xCoordinateAt(hover.index)}
              y2={baselineY}
              stroke={theme.palette.text.secondary}
              strokeWidth={1}
              strokeDasharray="3 3"
            />
            {layers.map((layer) => (
              <circle
                key={`dot-${layer.seriesIndex}`}
                cx={xCoordinateAt(hover.index)}
                cy={yCoordinateAt(layer.upper[hover.index])}
                r={3.6}
                fill={layer.color}
                stroke={theme.palette.background.paper}
                strokeWidth={1.5}
              />
            ))}
          </g>
        )}
      </svg>

      {hover != null && hoverRows.length > 0 && (
        <Box
          sx={{
            position: 'absolute',
            top: 8,
            left: tooltipLeft,
            pointerEvents: 'none',
            minWidth: TOOLTIP_WIDTH - 22,
            bgcolor: 'background.paper',
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: radii.lg,
            px: 1.625,
            py: 1.375,
            boxShadow: `0 16px 38px ${alpha(neutralColors.shadowDeep, 0.32)}`,
            zIndex: 6,
          }}
        >
          <Typography
            sx={{
              fontFamily: fontFamilies.display,
              fontWeight: 600,
              fontSize: 12,
              mb: 1,
            }}
          >
            {formatTooltipHeader(axisDates[hover.index])}
          </Typography>
          {hoverRows.map((row) => (
            <Box
              key={row.label}
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: 1,
                mt: 0.625,
                fontSize: 12.5,
                whiteSpace: 'nowrap',
                opacity: row.value === 0 ? 0.4 : 1,
              }}
            >
              <Box
                sx={{
                  width: 9,
                  height: 9,
                  borderRadius: '3px',
                  bgcolor: row.color,
                  flexShrink: 0,
                }}
              />
              <Box
                sx={{
                  flex: 1,
                  color: 'text.secondary',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
              >
                {row.label}
              </Box>
              <Box
                sx={{
                  fontWeight: 700,
                  color: 'text.primary',
                  fontVariantNumeric: 'tabular-nums',
                }}
              >
                {formatY(row.value)}
              </Box>
            </Box>
          ))}
          {stacked && (
            <Box
              sx={{
                display: 'flex',
                justifyContent: 'space-between',
                gap: 1.75,
                mt: 1.125,
                pt: 1,
                borderTop: '1px solid',
                borderColor: 'divider',
                fontWeight: 700,
                fontSize: 12.5,
                color: 'text.primary',
              }}
            >
              <span>Total</span>
              <span>{formatY(hoverTotal)}</span>
            </Box>
          )}
        </Box>
      )}
    </Box>
  );
};

export default AreaTrendChart;
