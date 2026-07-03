import { Box, alpha } from '@mui/material';
import SortRoundedIcon from '@mui/icons-material/SortRounded';
import ArrowDropDownIcon from '@mui/icons-material/ArrowDropDown';
import CheckIcon from '@mui/icons-material/Check';
import type { TraceSortKey } from '../../tracesApi';
import { radii } from '../../../../theme/theme';

const SORTS: Array<{ id: TraceSortKey; label: string }> = [
  { id: 'new', label: 'Newest' },
  { id: 'old', label: 'Oldest' },
  { id: 'slow', label: 'Slowest first' },
  { id: 'fast', label: 'Fastest first' },
  { id: 'spans', label: 'Most spans' },
  { id: 'tokens', label: 'Most tokens' },
  { id: 'err', label: 'Errors first' },
];

export interface TraceSortDropdownViewProps {
  sort: TraceSortKey;
  isOpen: boolean;
  onToggleOpen: () => void;
  onClose: () => void;
  onSelect: (sort: TraceSortKey) => void;
}

const TraceSortDropdownView = ({
  sort,
  isOpen,
  onToggleOpen,
  onClose,
  onSelect,
}: TraceSortDropdownViewProps) => {
  const currentSort = SORTS.find((sortOption) => sortOption.id === sort);

  return (
    <Box sx={{ position: 'relative' }}>
      <Box
        component="button"
        onClick={onToggleOpen}
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          height: 40,
          pl: 1.6,
          pr: 1.25,
          borderRadius: radii.lg,
          border: 1,
          borderColor: 'divider',
          bgcolor: 'background.paper',
          boxShadow: 1,
          color: 'text.primary',
          fontSize: 13,
          fontWeight: 500,
          cursor: 'pointer',
          whiteSpace: 'nowrap',
          '& svg.lead': { fontSize: 15, color: 'primary.main' },
        }}
      >
        <SortRoundedIcon className="lead" />
        {currentSort?.label.replace(' first', '')}
        <ArrowDropDownIcon sx={{ fontSize: 18, color: 'text.secondary' }} />
      </Box>
      {isOpen ? (
        <>
          <Box
            onClick={onClose}
            sx={{ position: 'fixed', inset: 0, zIndex: 30 }}
          />
          <Box
            sx={{
              position: 'absolute',
              top: 48,
              right: 0,
              zIndex: 40,
              minWidth: 184,
              bgcolor: 'background.paper',
              border: 1,
              borderColor: 'divider',
              borderRadius: radii.lg,
              boxShadow: 8,
              p: 0.75,
            }}
          >
            {SORTS.map((sortOption) => {
              const isActive = sortOption.id === sort;
              return (
                <Box
                  key={sortOption.id}
                  onClick={() => onSelect(sortOption.id)}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 1.25,
                    px: 1.4,
                    py: 1.1,
                    borderRadius: radii.sm,
                    fontSize: 13,
                    color: isActive ? 'primary.main' : 'text.primary',
                    bgcolor: isActive
                      ? (t) => alpha(t.palette.primary.main, 0.12)
                      : 'transparent',
                    fontWeight: isActive ? 600 : 400,
                    cursor: 'pointer',
                    '&:hover': { bgcolor: 'action.hover' },
                  }}
                >
                  {sortOption.label}
                  <CheckIcon sx={{ fontSize: 15, opacity: isActive ? 1 : 0 }} />
                </Box>
              );
            })}
          </Box>
        </>
      ) : null}
    </Box>
  );
};

export default TraceSortDropdownView;
