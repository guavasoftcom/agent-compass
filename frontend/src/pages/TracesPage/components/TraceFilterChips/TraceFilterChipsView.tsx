import { Box, alpha } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import ZoomInRoundedIcon from '@mui/icons-material/ZoomInRounded';
import type { FacetKey } from '../../tracesApi';

export interface TraceFilterChip {
  key: FacetKey | 'q';
  value: string;
  label: string;
}

export interface TraceFilterChipsViewProps {
  zoomLabel: string | null;
  chips: TraceFilterChip[];
  onRemoveChip: (chip: TraceFilterChip) => void;
  onClearAll: () => void;
  onClearZoom: () => void;
}

const TraceFilterChipsView = ({
  zoomLabel,
  chips,
  onRemoveChip,
  onClearAll,
  onClearZoom,
}: TraceFilterChipsViewProps) => {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        flexWrap: 'wrap',
        mb: 1.75,
      }}
    >
      {zoomLabel ? (
        <Box
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 0.75,
            height: 30,
            pl: 1.1,
            pr: 0.75,
            borderRadius: 1.1,
            bgcolor: (t) => alpha(t.palette.primary.main, 0.12),
            boxShadow: (t) =>
              `inset 0 0 0 1px ${alpha(t.palette.primary.main, 0.32)}`,
            color: 'primary.main',
            fontSize: 12,
            fontWeight: 600,
          }}
        >
          <ZoomInRoundedIcon sx={{ fontSize: 14 }} />
          {zoomLabel}
          <Box
            component="span"
            onClick={onClearZoom}
            sx={{
              display: 'grid',
              placeItems: 'center',
              width: 17,
              height: 17,
              borderRadius: 0.7,
              cursor: 'pointer',
              '&:hover': {
                bgcolor: (t) => alpha(t.palette.primary.main, 0.32),
              },
            }}
          >
            <CloseIcon sx={{ fontSize: 12 }} />
          </Box>
        </Box>
      ) : null}
      {chips.map((chip) => (
        <Box
          key={`${chip.key}:${chip.value}`}
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 0.75,
            height: 30,
            pl: 1.4,
            pr: 0.75,
            borderRadius: 1.1,
            bgcolor: (t) => alpha(t.palette.primary.main, 0.12),
            boxShadow: (t) =>
              `inset 0 0 0 1px ${alpha(t.palette.primary.main, 0.32)}`,
            color: 'primary.main',
            fontSize: 12,
            fontWeight: 600,
          }}
        >
          {chip.label}
          <Box
            component="span"
            onClick={() => onRemoveChip(chip)}
            sx={{
              display: 'grid',
              placeItems: 'center',
              width: 17,
              height: 17,
              borderRadius: 0.7,
              cursor: 'pointer',
              '&:hover': {
                bgcolor: (t) => alpha(t.palette.primary.main, 0.32),
              },
            }}
          >
            <CloseIcon sx={{ fontSize: 12 }} />
          </Box>
        </Box>
      ))}
      <Box
        component="span"
        onClick={onClearAll}
        sx={{
          fontSize: 12.5,
          fontWeight: 600,
          color: 'text.secondary',
          cursor: 'pointer',
          ml: 0.5,
          whiteSpace: 'nowrap',
          '&:hover': { color: 'primary.main' },
        }}
      >
        Clear all
      </Box>
    </Box>
  );
};

export default TraceFilterChipsView;
