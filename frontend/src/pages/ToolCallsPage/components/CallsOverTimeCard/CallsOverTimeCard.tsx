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
import { useMemo } from 'react';
import { useTheme } from '@mui/material';
import { colorForIndex } from '../../../../theme/theme';
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
