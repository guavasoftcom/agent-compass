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
import { alpha, Box, Tooltip } from '@mui/material';
import { auroraColors, gradients } from '../../../../theme/colors';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';
import { useNowTick } from '../../../../lib/useNowTick';
import { formatDuration } from '../../../TracesPage/tracesApi';

export interface TraceLiveChipProps {
  earliestStartMs: number;
}

// Design handoff (Trace In-Progress Indicator): replaces the old full-width "still running"
// banner with a compact ticking chip inline in the breadcrumb. Only ever rendered by the caller
// while inProgress is true, so useNowTick's interval only runs while this is mounted — a
// finished trace never ticks.
const TraceLiveChip = ({ earliestStartMs }: TraceLiveChipProps) => {
  const now = useNowTick();
  const elapsedMs = Math.max(0, now - earliestStartMs);
  const elapsedLabel = formatDuration(elapsedMs * 1e6);

  return (
    <Tooltip title="This trace is still running. This page updates automatically." arrow>
      <Box
        component="span"
        role="status"
        aria-label="Trace still running"
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: '7px',
          padding: '6px 12px 6px 10px',
          borderRadius: radii.pill,
          background: gradients.liveTailAccessible,
          boxShadow: `0 6px 16px ${alpha(auroraColors.greenDeepText, 0.4)}`,
          cursor: 'help',
        }}
      >
        <Box
          component="span"
          sx={{
            width: 8,
            height: 8,
            flexShrink: 0,
            borderRadius: '50%',
            bgcolor: 'common.white',
            animation: 'sessionRunningPulse 1.3s ease-in-out infinite',
            '@keyframes sessionRunningPulse': {
              '0%, 100%': { opacity: 1, transform: 'scale(1)' },
              '50%': { opacity: 0.4, transform: 'scale(0.8)' },
            },
          }}
        />
        <Box
          component="span"
          sx={{
            fontFamily: fontFamilies.display,
            fontWeight: 700,
            fontSize: 11.5,
            letterSpacing: '.3px',
            color: 'common.white',
          }}
        >
          LIVE
        </Box>
        <Box
          component="span"
          sx={{
            fontFamily: fontFamilies.mono,
            fontWeight: 600,
            fontSize: 11.5,
            fontVariantNumeric: 'tabular-nums',
            color: 'common.white',
            opacity: 0.92,
          }}
        >
          {elapsedLabel}
        </Box>
      </Box>
    </Tooltip>
  );
};

export default TraceLiveChip;
