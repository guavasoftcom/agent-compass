import { useMemo } from 'react';
import { useTheme } from '@mui/material';
import { colorForIndex } from '../../../../theme';
import type { ToolCallTimeseries } from '../../../../api';
import CallsOverTimeCardView, {
  type LineSeries,
} from './CallsOverTimeCardView';

export interface CallsOverTimeCardProps {
  timeseries: ToolCallTimeseries | null;
  isLoading: boolean;
}

const CallsOverTimeCard = ({
  timeseries,
  isLoading,
}: CallsOverTimeCardProps) => {
  const theme = useTheme();

  const axisDates = useMemo(
    () => (timeseries?.points ?? []).map((point) => new Date(point.timestamp)),
    [timeseries],
  );

  const series = useMemo<LineSeries[]>(() => {
    if (!timeseries || timeseries.tools.length === 0) {
      return [];
    }
    return timeseries.tools.map((tool, columnIndex) => ({
      label: tool,
      data: timeseries.points.map((point) => point.counts[columnIndex] ?? 0),
      area: true,
      stack: 'tools',
      showMark: false,
      color:
        tool === 'Other'
          ? theme.palette.action.disabled
          : colorForIndex(columnIndex),
    }));
  }, [timeseries, theme]);

  const hasData = series.length > 0 && axisDates.length >= 2;
  const emptyMessage =
    axisDates.length === 1
      ? 'Only one bucket in this window — need at least two to plot a trend.'
      : 'No data in this window.';

  return (
    <CallsOverTimeCardView
      axisDates={axisDates}
      series={series}
      hasData={hasData}
      isLoading={isLoading}
      emptyMessage={emptyMessage}
    />
  );
};

export default CallsOverTimeCard;
