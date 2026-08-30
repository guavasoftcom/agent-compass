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
import { Box } from '@mui/material';
import ViewStreamIcon from '@mui/icons-material/ViewStream';
import TableRowsIcon from '@mui/icons-material/TableRows';
import SegmentedToggle from '../SegmentedToggle/SegmentedToggle';
import { radii } from '../../theme/theme';

export type StreamTableView = 'stream' | 'table';

export interface StreamTableToggleProps {
  value: StreamTableView;
  onChange: (next: StreamTableView) => void;
}

const VIEW_OPTIONS = [
  {
    value: 'stream' as StreamTableView,
    label: (
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.9, '& svg': { fontSize: 16 } }}>
        <ViewStreamIcon />
        Stream
      </Box>
    ),
  },
  {
    value: 'table' as StreamTableView,
    label: (
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.9, '& svg': { fontSize: 16 } }}>
        <TableRowsIcon />
        Table
      </Box>
    ),
  },
];

const StreamTableToggle = ({ value, onChange }: StreamTableToggleProps) => {
  return (
    <SegmentedToggle
      options={VIEW_OPTIONS}
      value={value}
      onChange={onChange}
      sx={{ borderRadius: radii.lg, height: 40, alignItems: 'center' }}
    />
  );
};

export default StreamTableToggle;
