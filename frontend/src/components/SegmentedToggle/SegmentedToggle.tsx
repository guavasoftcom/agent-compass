import type { ReactNode } from 'react';
import { Box } from '@mui/material';

export interface SegmentedToggleOption<T> {
  value: T;
  label: ReactNode;
}

export interface SegmentedToggleProps<T> {
  options: SegmentedToggleOption<T>[];
  value: T;
  onChange: (value: T) => void;
}

// A pill-style segmented control: a tinted track holding a row of segments, the
// active one lifted onto a paper background. Shared by the per-page "rows per
// page" / "split by" toggles so they all read identically.
const SegmentedToggle = <T,>({ options, value, onChange }: SegmentedToggleProps<T>) => (
  <Box sx={{ display: 'inline-flex', bgcolor: 'action.hover', borderRadius: 1.25, p: '3px', gap: '2px' }}>
    {options.map((option) => {
      const isActive = option.value === value;
      return (
        <Box
          key={String(option.value)}
          component="button"
          type="button"
          onClick={() => onChange(option.value)}
          sx={{
            border: 'none',
            cursor: 'pointer',
            fontFamily: "'Sora', sans-serif",
            fontSize: 12.5,
            fontWeight: 600,
            px: 1.4,
            py: 0.5,
            borderRadius: 1,
            color: isActive ? 'primary.main' : 'text.secondary',
            bgcolor: isActive ? 'background.paper' : 'transparent',
            boxShadow: isActive ? 1 : 'none',
            '&:hover': { color: 'text.primary' },
          }}
        >
          {option.label}
        </Box>
      );
    })}
  </Box>
);

export default SegmentedToggle;
