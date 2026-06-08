import { useMemo } from 'react';
import { colorForIndex } from '../../../../theme';
import type { ToolLatencyRow } from '../../../../api';
import ToolLatencyCardView, {
  type LatencyBarSeries,
} from './ToolLatencyCardView';

export interface ToolLatencyCardProps {
  latencyRows: ToolLatencyRow[];
  isLoading: boolean;
}

const formatSeconds = (value: number | null | undefined): string => {
  const v = value ?? 0;
  return v < 1 ? `${(v * 1000).toFixed(0)} ms` : `${v.toFixed(2)} s`;
};

const ToolLatencyCard = ({ latencyRows, isLoading }: ToolLatencyCardProps) => {
  const chartRows = useMemo(() => latencyRows.slice(0, 10), [latencyRows]);

  const series = useMemo<LatencyBarSeries[]>(
    () => [
      {
        label: 'Typical',
        data: chartRows.map((row) => row.p50Ms / 1000),
        stack: 'latency',
        color: colorForIndex(2),
        valueFormatter: formatSeconds,
      },
      {
        label: 'Worst 5%',
        data: chartRows.map((row) =>
          Math.max(0, (row.p95Ms - row.p50Ms) / 1000),
        ),
        stack: 'latency',
        color: colorForIndex(1),
        valueFormatter: formatSeconds,
      },
    ],
    [chartRows],
  );

  const hasData = chartRows.length > 0;
  const height = Math.max(220, chartRows.length * 36 + 80);
  const toolLabels = chartRows.map((row) => row.tool);
  // Right-size the y-axis label column to the longest label so bars get the rest.
  // ~7px/char is a decent estimate for the default MUI tick font; +16px padding.
  const longestLabelLength = toolLabels.reduce(
    (max, label) => Math.max(max, label.length),
    0,
  );
  const yAxisWidth = Math.min(
    200,
    Math.max(60, longestLabelLength * 7 + 16),
  );

  return (
    <ToolLatencyCardView
      toolLabels={toolLabels}
      series={series}
      hasData={hasData}
      isLoading={isLoading}
      height={height}
      yAxisWidth={yAxisWidth}
    />
  );
};

export default ToolLatencyCard;
