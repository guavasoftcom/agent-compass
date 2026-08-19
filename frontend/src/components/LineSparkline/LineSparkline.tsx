import { alpha, useTheme } from '@mui/material';
import { isSparseCounter } from '../../lib/format';

export interface LineSparklineProps {
  values: number[];
  height?: number;
  color?: string;
  strokeWidth?: number;
}

const VIEW_WIDTH = 110;
const MAX_BAR_WIDTH = 9;
const BAR_CORNER_RADIUS = 1.5;

/**
 * Shared sparkline rendered as an inline SVG, in one of two forms depending on
 * `values`: an area+line for a continuous series, or per-bucket bars for a
 * sparse whole-number counter (same `isSparseCounter` threshold `MetricTrendCard`
 * uses to switch its detail chart, so a card never shows bars while its chart
 * shows an area). Used by MetricKpiStrip (height=24) and SessionsKpiStrip
 * (height=36). Returns null when fewer than two data points are provided,
 * avoiding divide-by-zero in the x-coordinate calculation and NaN from
 * Math.max/min on an empty spread.
 */
const LineSparkline = ({ values, height = 36, color, strokeWidth = 1.7 }: LineSparklineProps) => {
  const theme = useTheme();
  const resolvedColor = color ?? theme.palette.primary.main;

  if (values.length < 2) {
    return null;
  }

  const xForIndex = (index: number) => (index * VIEW_WIDTH) / (values.length - 1);

  if (isSparseCounter(values)) {
    const ceiling = Math.max(1, Math.round(Math.max(...values))) + 1;
    const barWidth = Math.min(MAX_BAR_WIDTH, (VIEW_WIDTH / values.length) * 0.9);
    const baselineY = height - 2;
    const yForValue = (value: number) => baselineY - (value / ceiling) * (height - 5);

    return (
      <svg
        viewBox={`0 0 ${VIEW_WIDTH} ${height}`}
        width="100%"
        height={height}
        preserveAspectRatio="none"
        style={{ display: 'block' }}
      >
        {values.map((value, index) => {
          // An empty bucket draws nothing — absence is absence, not a sliver on the baseline.
          if (value <= 0) {
            return null;
          }
          const barTop = yForValue(value);
          return (
            <rect
              key={index}
              x={xForIndex(index) - barWidth / 2}
              y={barTop}
              width={barWidth}
              height={baselineY - barTop}
              rx={BAR_CORNER_RADIUS}
              fill={resolvedColor}
              fillOpacity={0.8}
            />
          );
        })}
        <line
          x1={0}
          y1={baselineY}
          x2={VIEW_WIDTH}
          y2={baselineY}
          stroke={resolvedColor}
          strokeOpacity={0.28}
          strokeWidth={1}
          vectorEffect="non-scaling-stroke"
        />
      </svg>
    );
  }

  const maxValue = Math.max(...values);
  const minValue = Math.min(...values);
  const range = maxValue - minValue || 1;

  const yForValue = (value: number) => height - 2 - ((value - minValue) / range) * (height - 6);

  const linePath = values
    .map(
      (value, index) =>
        `${index === 0 ? 'M' : 'L'}${xForIndex(index).toFixed(1)},${yForValue(value).toFixed(1)}`,
    )
    .join('');
  const areaPath = `${linePath}L${VIEW_WIDTH},${height}L0,${height}Z`;

  return (
    <svg
      viewBox={`0 0 ${VIEW_WIDTH} ${height}`}
      width="100%"
      height={height}
      preserveAspectRatio="none"
      style={{ display: 'block' }}
    >
      <path d={areaPath} fill={alpha(resolvedColor, 0.13)} />
      <path
        d={linePath}
        fill="none"
        stroke={resolvedColor}
        strokeWidth={strokeWidth}
        strokeLinejoin="round"
        strokeLinecap="round"
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  );
};

export default LineSparkline;
