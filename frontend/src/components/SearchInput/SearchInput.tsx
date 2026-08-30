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
import { Box, InputBase, alpha } from '@mui/material';
import type { SxProps, Theme } from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import { radii } from '../../theme/theme';

export interface SearchInputProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  /** Applied to the wrapper Box — use for outer margins (the facet rails pass mx/mt/mb here). */
  sx?: SxProps<Theme>;
}

// Search field with a leading magnifier. The icon is absolutely positioned, so
// the relative wrapper Box is its containing block and ships with the component;
// zIndex + pointerEvents keep it painted above (and click-through to) the input.
const SearchInput = ({ value, onChange, placeholder, sx }: SearchInputProps) => {
  return (
    <Box sx={{ position: 'relative', ...sx }}>
      <SearchIcon sx={{ position: 'absolute', left: 11, top: '50%', transform: 'translateY(-50%)', fontSize: 17, color: 'text.disabled', pointerEvents: 'none', zIndex: 1 }} />
      <InputBase
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        sx={{
          width: '100%',
          height: 38,
          borderRadius: radii.sm,
          border: 1,
          borderColor: 'divider',
          bgcolor: 'background.paper',
          fontSize: 13,
          pl: '33px',
          pr: 1.5,
          color: 'text.primary',
          transition: 'border-color .12s, box-shadow .12s',
          '&.Mui-focused': {
            borderColor: 'primary.main',
            boxShadow: (t) => `0 0 0 3px ${alpha(t.palette.primary.main, 0.18)}`,
          },
        }}
      />
    </Box>
  );
};

export default SearchInput;
