import { Box } from '@mui/material';
import ViewStreamIcon from '@mui/icons-material/ViewStream';
import TableRowsIcon from '@mui/icons-material/TableRows';
import SegmentedToggle from '../SegmentedToggle/SegmentedToggle';

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
      sx={{ borderRadius: 1.5, height: 40, alignItems: 'center' }}
    />
  );
};

export default StreamTableToggle;
