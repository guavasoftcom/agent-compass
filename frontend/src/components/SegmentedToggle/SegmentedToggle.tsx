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
import type { ReactNode } from 'react';
import { Box, type SxProps, type Theme } from '@mui/material';
import { fontFamilies } from '../../theme/typography';
import { radii } from '../../theme/theme';

export interface SegmentedToggleOption<T> {
  value: T;
  label: ReactNode;
}

export interface SegmentedToggleProps<T> {
  options: SegmentedToggleOption<T>[];
  value: T;
  onChange: (value: T) => void;
  /** Optional sx overrides applied to the outer track Box. */
  sx?: SxProps<Theme>;
}

// A pill-style segmented control: a tinted track holding a row of segments, the
// active one lifted onto a paper background. Shared by the per-page "rows per
// page" / "split by" toggles so they all read identically.
const SegmentedToggle = <T,>({ options, value, onChange, sx }: SegmentedToggleProps<T>) => (
  <Box sx={[{ display: 'inline-flex', bgcolor: 'action.hover', borderRadius: radii.sm, p: '3px', gap: '2px' }, ...(Array.isArray(sx) ? sx : sx ? [sx] : [])]}>
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
            fontFamily: fontFamilies.display,
            fontSize: 12.5,
            fontWeight: 600,
            px: 1.4,
            py: 0.5,
            borderRadius: radii.sm,
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
