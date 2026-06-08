import { Box, Paper, Stack, Typography } from '@mui/material';
import AreaTrendChart, { AreaTrendLegend, useSeriesVisibility } from '../../../../components/AreaTrendChart';

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

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack
        direction="row"
        sx={{ alignItems: 'baseline', justifyContent: 'space-between', mb: 1, gap: 2, flexWrap: 'wrap' }}
      >
        <Typography variant="subtitle1">Calls over time</Typography>
        {hasData && (
          <AreaTrendLegend
            items={series.map((s) => ({ label: s.label, color: s.color }))}
            visibility={visibility}
          />
        )}
      </Stack>

      {!hasData && !isLoading ? (
        <Box sx={{ height: 280, display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
          <Typography color="text.secondary">{emptyMessage}</Typography>
        </Box>
      ) : (
        <AreaTrendChart
          axisDates={axisDates}
          series={series.map((s) => ({ label: s.label, data: s.data, color: s.color }))}
          yLabel="calls"
          activeStates={visibility.active}
          focusedLabel={visibility.focused}
        />
      )}
    </Paper>
  );
};

export default CallsOverTimeCardView;
