import { alpha, useTheme } from '@mui/material';

export interface LineSparklineProps {
  values: number[];
  height?: number;
  color?: string;
  strokeWidth?: number;
}

/**
 * Shared area+line sparkline rendered as an inline SVG.
 * Used by MetricKpiStrip (height=24) and SessionsKpiStrip (height=36).
 * Returns null when fewer than two data points are provided, avoiding
 * divide-by-zero in the x-coordinate calculation and NaN from Math.max/min
 * on an empty spread.
 */
const LineSparkline = ({ values, height = 36, color, strokeWidth = 1.7 }: LineSparklineProps) => {
  const theme = useTheme();
  const resolvedColor = color ?? theme.palette.primary.main;

  if (values.length < 2) {
    return null;
  }

  const viewWidth = 120;
  const maxValue = Math.max(...values);
  const minValue = Math.min(...values);
  const range = maxValue - minValue || 1;

  const xForIndex = (index: number) => (index * viewWidth) / (values.length - 1);
  const yForValue = (value: number) => height - 2 - ((value - minValue) / range) * (height - 6);

  const linePath = values
    .map(
      (value, index) =>
        `${index === 0 ? 'M' : 'L'}${xForIndex(index).toFixed(1)},${yForValue(value).toFixed(1)}`,
    )
    .join('');
  const areaPath = `${linePath}L${viewWidth},${height}L0,${height}Z`;

  return (
    <svg
      viewBox={`0 0 ${viewWidth} ${height}`}
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
