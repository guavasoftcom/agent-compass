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
import CloseIcon from '@mui/icons-material/Close';
import ZoomInRoundedIcon from '@mui/icons-material/ZoomInRounded';
import type { FacetKey } from '../../tracesApi';
import { radii } from '../../../../theme/theme';

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
            borderRadius: radii.sm,
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
              borderRadius: radii.xs,
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
            borderRadius: radii.sm,
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
              borderRadius: radii.xs,
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
