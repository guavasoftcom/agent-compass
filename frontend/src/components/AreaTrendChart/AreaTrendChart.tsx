import { useEffect, useId, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { Box, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';

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
}

const PAD = { left: 52, right: 16, top: 16, bottom: 44 };

// Fine "nice-max" ladder so the data peak fills most of the plot height
// (a coarse 1/2/5/10 ladder leaves big dead space above the curve).
const NICE_STEPS = [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10];
const niceMax = (value: number): number => {
  if (value <= 0) {
    return 1;
  }
  const pow = 10 ** Math.floor(Math.log10(value));
  const f = value / pow;
  const step = NICE_STEPS.find((s) => f <= s) ?? 10;
  return step * pow;
};

const defaultFormatX = (date: Date): string =>
  date.toLocaleTimeString([], { hour: 'numeric' });

const formatXDate = (date: Date): string =>
  date.toLocaleDateString([], { month: 'short', day: 'numeric' });

const defaultTooltipHeader = (date: Date): string =>
  date.toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

interface Layer {
  seriesIndex: number;
  label: string;
  color: string;
  /** Lower edge of this layer's band (cumulative floor when stacked; axis floor when not). */
  lower: number[];
  /** Drawn top of this layer (cumulative top when stacked; the series value when not). */
  upper: number[];
}

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
  formatY = (v) => String(Math.round(v)),
  formatX = defaultFormatX,
  formatTooltipHeader = defaultTooltipHeader,
  height = 300,
  activeStates,
  focusedLabel = null,
  stacked = true,
  yScale = 'linear',
}: AreaTrendChartProps) => {
  const theme = useTheme();
  const gradId = useId().replace(/:/g, '');
  const ref = useRef<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(800);
  const [hover, setHover] = useState<{ index: number; leftPx: number } | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) {
      return;
    }
    const observer = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width;
      if (w) {
        setWidth(w);
      }
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const isLog = yScale === 'log';
  const n = axisDates.length;
  const plotW = width - PAD.left - PAD.right;
  const plotH = height - PAD.top - PAD.bottom;
  const baseY = PAD.top + plotH;

  const isActive = (k: number): boolean => (activeStates ? activeStates[k] !== false : true);

  // Build the visible layers. Stacked → cumulative bands; unstacked → each series
  // is its own line, its band filled down to the shared baseline (set below once the
  // domain is known).
  const layers: Layer[] = [];
  const running = new Array(n).fill(0);
  series.forEach((s, k) => {
    if (!isActive(k)) {
      return;
    }
    if (stacked) {
      const lower = running.slice();
      const upper = running.map((v, i) => v + (s.data[i] ?? 0));
      layers.push({ seriesIndex: k, label: s.label, color: s.color, lower, upper });
      for (let i = 0; i < n; i += 1) {
        running[i] = upper[i];
      }
    } else {
      layers.push({
        seriesIndex: k,
        label: s.label,
        color: s.color,
        lower: [],
        upper: (s.data ?? []).slice(0, n).map((v) => v ?? 0),
      });
    }
  });

  // --- Y domain --------------------------------------------------------------
  let yFloor: number;
  let yCeil: number;
  let yTicks: number[];

  if (isLog) {
    let minPositive = Infinity;
    let maxValue = 1;
    for (const layer of layers) {
      for (const v of layer.upper) {
        if (v > 0 && v < minPositive) {
          minPositive = v;
        }
        if (v > maxValue) {
          maxValue = v;
        }
      }
    }
    if (!Number.isFinite(minPositive)) {
      minPositive = 1;
    }
    // Floor a decade below the smallest positive value; ceiling at/above the max.
    const floorExp = Math.floor(Math.log10(minPositive)) - 1;
    const ceilExp = Math.max(floorExp + 1, Math.ceil(Math.log10(maxValue)));
    yFloor = 10 ** floorExp;
    yCeil = 10 ** ceilExp;
    yTicks = [];
    for (let exp = floorExp; exp <= ceilExp; exp += 1) {
      yTicks.push(10 ** exp);
    }
  } else {
    yFloor = 0;
    let top = 1;
    for (const layer of layers) {
      for (const v of layer.upper) {
        if (v > top) {
          top = v;
        }
      }
    }
    yCeil = niceMax(top);
    yTicks = Array.from({ length: 6 }, (_, i) => (yCeil * i) / 5);
  }

  // Shared baseline for unstacked bands (axis floor).
  if (!stacked) {
    for (const layer of layers) {
      layer.lower = layer.upper.map(() => yFloor);
    }
  }

  const xAt = (i: number) => (n <= 1 ? PAD.left : PAD.left + (i * plotW) / (n - 1));
  const yAt = (value: number): number => {
    if (isLog) {
      const clamped = Math.max(value, yFloor);
      const t =
        (Math.log10(clamped) - Math.log10(yFloor)) /
        (Math.log10(yCeil) - Math.log10(yFloor));
      return PAD.top + (1 - t) * plotH;
    }
    return PAD.top + (1 - value / yCeil) * plotH;
  };

  const bandPath = (layer: Layer): string => {
    // No points → empty path (avoids an invalid lone "Z" during loading).
    if (layer.upper.length === 0) {
      return '';
    }
    let d = layer.upper.map((v, i) => `${i ? 'L' : 'M'}${xAt(i)},${yAt(v)}`).join('');
    for (let i = layer.lower.length - 1; i >= 0; i -= 1) {
      d += `L${xAt(i)},${yAt(layer.lower[i])}`;
    }
    return `${d}Z`;
  };

  const linePath = (layer: Layer): string =>
    layer.upper.map((v, i) => `${i ? 'L' : 'M'}${xAt(i)},${yAt(v)}`).join('');

  const xTickCount = Math.min(8, n);
  const xTickIdx =
    n === 0
      ? []
      : n === 1
        ? [0]
        : Array.from({ length: xTickCount }, (_, i) =>
            Math.round((i * (n - 1)) / (xTickCount - 1)),
          );

  const gridColor = theme.palette.divider;
  const labelColor = theme.palette.text.secondary;

  // Two-line date+time labels once the window spans more than 2h; a single time
  // line below that. PAD.bottom (44) reserves room for the second line.
  const spanMs = n >= 2 ? axisDates[n - 1].getTime() - axisDates[0].getTime() : 0;
  const twoLine = spanMs > 2 * 60 * 60 * 1000;

  // Line opacity reflects focus; bands additionally fade when unstacked so the
  // overlapping fills stay legible.
  const lineOpacityFor = (label: string): number =>
    !focusedLabel || focusedLabel === label ? 1 : 0.12;
  const bandOpacityFor = (label: string): number =>
    lineOpacityFor(label) * (stacked ? 1 : 0.35);

  const handleMove = (event: ReactMouseEvent<SVGSVGElement>) => {
    if (n < 1) {
      return;
    }
    const rect = event.currentTarget.getBoundingClientRect();
    const localX = ((event.clientX - rect.left) / rect.width) * width;
    const raw = n <= 1 ? 0 : Math.round(((localX - PAD.left) * (n - 1)) / plotW);
    const index = Math.max(0, Math.min(n - 1, raw));
    setHover({ index, leftPx: event.clientX - rect.left });
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
  const TIP_W = 190;
  const tipLeft = hover != null
    ? Math.max(4, hover.leftPx > width - TIP_W ? hover.leftPx - TIP_W : hover.leftPx + 16)
    : 0;

  return (
    <Box ref={ref} sx={{ width: '100%', position: 'relative' }}>
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
            <linearGradient key={layer.seriesIndex} id={`${gradId}-${layer.seriesIndex}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor={layer.color} stopOpacity={0.4} />
              <stop offset="1" stopColor={layer.color} stopOpacity={0.04} />
            </linearGradient>
          ))}
        </defs>

        {yTicks.map((v, i) => (
          <g key={i}>
            <line
              x1={PAD.left}
              y1={yAt(v)}
              x2={width - PAD.right}
              y2={yAt(v)}
              stroke={gridColor}
              strokeDasharray="3 4"
            />
            <text
              x={PAD.left - 8}
              y={yAt(v) + 4}
              textAnchor="end"
              fill={labelColor}
              fontSize={11}
              fontFamily={theme.typography.fontFamily}
            >
              {formatY(v)}
            </text>
          </g>
        ))}

        {layers.map((layer) => (
          <path
            key={`band-${layer.seriesIndex}`}
            d={bandPath(layer)}
            fill={`url(#${gradId}-${layer.seriesIndex})`}
            opacity={bandOpacityFor(layer.label)}
            style={{ transition: 'opacity .12s' }}
          />
        ))}

        {layers.map((layer) => (
          <path
            key={`line-${layer.seriesIndex}`}
            d={linePath(layer)}
            fill="none"
            stroke={layer.color}
            strokeWidth={2.5}
            strokeLinejoin="round"
            opacity={lineOpacityFor(layer.label)}
            style={{ transition: 'opacity .12s' }}
          />
        ))}

        {xTickIdx.map((idx) => {
          const date = axisDates[idx];
          if (twoLine) {
            return (
              <text
                key={idx}
                x={xAt(idx)}
                y={height - 26}
                textAnchor="middle"
                fill={labelColor}
                fontSize={11}
                fontFamily={theme.typography.fontFamily}
              >
                <tspan x={xAt(idx)} dy="0">{formatXDate(date)}</tspan>
                <tspan x={xAt(idx)} dy="14">{formatX(date)}</tspan>
              </text>
            );
          }
          return (
            <text
              key={idx}
              x={xAt(idx)}
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
          y={PAD.top + plotH / 2}
          textAnchor="middle"
          fill={labelColor}
          fontSize={11}
          fontFamily={theme.typography.fontFamily}
          transform={`rotate(-90 12 ${PAD.top + plotH / 2})`}
        >
          {yLabel}
        </text>

        {/* Hover crosshair: vertical guide + a dot on each visible line */}
        {hover != null && (
          <g pointerEvents="none">
            <line
              x1={xAt(hover.index)}
              y1={PAD.top}
              x2={xAt(hover.index)}
              y2={baseY}
              stroke={theme.palette.text.secondary}
              strokeWidth={1}
              strokeDasharray="3 3"
            />
            {layers.map((layer) => (
              <circle
                key={`dot-${layer.seriesIndex}`}
                cx={xAt(hover.index)}
                cy={yAt(layer.upper[hover.index])}
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
            left: tipLeft,
            pointerEvents: 'none',
            minWidth: TIP_W - 22,
            bgcolor: 'background.paper',
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 1.5,
            px: 1.625,
            py: 1.375,
            boxShadow: '0 16px 38px rgba(10,6,30,.32)',
            zIndex: 6,
          }}
        >
          <Typography sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 600, fontSize: 12, mb: 1 }}>
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
              <Box sx={{ width: 9, height: 9, borderRadius: '3px', bgcolor: row.color, flexShrink: 0 }} />
              <Box sx={{ flex: 1, color: 'text.secondary', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {row.label}
              </Box>
              <Box sx={{ fontWeight: 700, color: 'text.primary', fontVariantNumeric: 'tabular-nums' }}>
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
