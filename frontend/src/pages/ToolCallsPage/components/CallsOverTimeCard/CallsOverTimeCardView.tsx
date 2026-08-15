import { Box, Tooltip, Typography } from '@mui/material';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import AreaTrendChart, {
  AreaTrendLegend,
  useSeriesVisibility,
} from '../../../../components/AreaTrendChart';
import ChartCard from '../../../../components/ChartCard';

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
      items={series.map((seriesItem) => ({
        label: seriesItem.label,
        color: seriesItem.color,
      }))}
      visibility={visibility}
    />
  ) : undefined;

  const titleContent = (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
      <span>Calls over time</span>
      <Tooltip
        title="Areas sit on top of one another — the top edge is the total across all tools, and each band's thickness is that tool's own count. Hover any hour for the per-tool split and its total."
        arrow
      >
        <InfoOutlinedIcon
          sx={{ fontSize: 18, color: 'text.secondary', cursor: 'help' }}
        />
      </Tooltip>
    </Box>
  );

  return (
    <ChartCard title={titleContent} legend={legend}>
      {!hasData && !isLoading ? (
        <Box
          sx={{
            height: 280,
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
          }}
        >
          <Typography color="text.secondary">{emptyMessage}</Typography>
        </Box>
      ) : (
        <AreaTrendChart
          axisDates={axisDates}
          series={series.map((seriesItem) => ({
            label: seriesItem.label,
            data: seriesItem.data,
            color: seriesItem.color,
          }))}
          yLabel="calls (stacked)"
          activeStates={visibility.active}
          focusedLabel={visibility.focused}
        />
      )}
    </ChartCard>
  );
};

export default CallsOverTimeCardView;
