import { Box } from '@mui/material';
import ViewStreamIcon from '@mui/icons-material/ViewStream';
import TableRowsIcon from '@mui/icons-material/TableRows';

export type TraceView = 'stream' | 'table';

export interface TraceViewToggleProps {
  view: TraceView;
  onViewChange: (view: TraceView) => void;
}

const VIEW_OPTIONS: Array<{ id: TraceView; label: string }> = [
  { id: 'stream', label: 'Stream' },
  { id: 'table', label: 'Table' },
];

const TraceViewToggle = ({ view, onViewChange }: TraceViewToggleProps) => {
  return (
    <Box
      sx={{
        display: 'inline-flex',
        bgcolor: 'action.hover',
        borderRadius: 1.5,
        p: '3px',
        gap: '2px',
        height: 40,
        alignItems: 'center',
      }}
    >
      {VIEW_OPTIONS.map((option) => {
        const isActive = view === option.id;
        return (
          <Box
            key={option.id}
            component="button"
            onClick={() => onViewChange(option.id)}
            sx={{
              border: 'none',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              gap: 0.9,
              height: 34,
              px: 1.6,
              borderRadius: 1,
              fontFamily: "'Sora', sans-serif",
              fontSize: 13,
              fontWeight: 600,
              color: isActive ? 'primary.main' : 'text.secondary',
              bgcolor: isActive ? 'background.paper' : 'transparent',
              boxShadow: isActive ? 1 : 'none',
              '&:hover': { color: isActive ? 'primary.main' : 'text.primary' },
              '& svg': { fontSize: 16 },
            }}
          >
            {option.id === 'stream' ? <ViewStreamIcon /> : <TableRowsIcon />}
            {option.label}
          </Box>
        );
      })}
    </Box>
  );
};

export default TraceViewToggle;
