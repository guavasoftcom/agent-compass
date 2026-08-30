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
  { id: 'cost', label: 'Highest cost' },
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
