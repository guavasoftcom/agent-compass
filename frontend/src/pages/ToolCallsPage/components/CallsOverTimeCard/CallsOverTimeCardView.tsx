import { Box, Typography } from '@mui/material';
import AreaTrendChart, { AreaTrendLegend, useSeriesVisibility } from '../../../../components/AreaTrendChart';
import ChartCard from '../../../../components/ChartCard/ChartCard';

export interface LineSeries {
  label: string;
  data: number[];
  area: true;
  stack: string;
  showMark: false;
  color: string;
}

export interface CallsOverTimeCardViewProps {
  axisDates: Date[];
  series: LineSeries[];
  hasData: boolean;
  isLoading: boolean;
  emptyMessage: string;
}

/**
 * "Calls over time" — Aurora stacked-area trend (shared AreaTrendChart). The top-right
 * legend is interactive: hover a tool to spotlight its band, click to toggle it off/on.
 * Hovering the plot drops a ranked crosshair tooltip (every visible tool at that bucket,
 * high→low, plus a Total) so individual lines are easy to pinpoint.
 */
const CallsOverTimeCardView = ({
  axisDates,
  series,
  hasData,
  isLoading,
  emptyMessage,
}: CallsOverTimeCardViewProps) => {
  const visibility = useSeriesVisibility(series.length);

  const legend = hasData ? (
    <AreaTrendLegend
      items={series.map((seriesItem) => ({ label: seriesItem.label, color: seriesItem.color }))}
      visibility={visibility}
    />
  ) : undefined;

  return (
    <ChartCard title="Calls over time" legend={legend}>
      {!hasData && !isLoading ? (
        <Box sx={{ height: 280, display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
          <Typography color="text.secondary">{emptyMessage}</Typography>
        </Box>
      ) : (
        <AreaTrendChart
          axisDates={axisDates}
          series={series.map((seriesItem) => ({ label: seriesItem.label, data: seriesItem.data, color: seriesItem.color }))}
          yLabel="calls"
          activeStates={visibility.active}
          focusedLabel={visibility.focused}
        />
      )}
    </ChartCard>
  );
};

export default CallsOverTimeCardView;
