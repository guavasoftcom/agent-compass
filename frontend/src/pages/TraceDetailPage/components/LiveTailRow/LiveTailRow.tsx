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
import { Box } from '@mui/material';
import { RunningIndicator } from '../../../../components/RunningIndicator';
import { auroraColors, gradients, neutralColors } from '../../../../theme/colors';

export interface LiveTailRowProps {
  gridColumns: string;
  // Percent of the visible zoom window, same geometry TraceDetailPageView already
  // computes for every SpanWaterfallRow — this component does no time math of its own.
  left: number;
  right: number;
}

// Design handoff (Trace In-Progress Indicator): TraceRow.inProgress means the root span
// hasn't been exported yet, not "the last known span is still open" — so there is no single
// row to animate as growing. This is a new trailing segment appended after the last
// currently-known span instead, anchored at `left` (the max offMs + durationMs across visible
// spans) and growing to `right` (now, capped at the zoom view's right edge) on every tick.
// Duration is deliberately not stated as a number — there's no known duration yet, only
// elapsed-since-last-known-activity.
const LiveTailRow = ({ gridColumns, left, right }: LiveTailRowProps) => {
  const width = Math.max(0, right - left);
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: gridColumns,
        alignItems: 'center',
        height: 30,
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 0.9,
          minWidth: 0,
          pl: '10px',
        }}
      >
        <RunningIndicator
          tooltip="More spans are still arriving for this trace."
          ariaLabel="More spans are still arriving"
        />
        <Box
          component="span"
          sx={{
            typography: 'mono',
            fontSize: 11,
            color: 'text.disabled',
            whiteSpace: 'nowrap',
          }}
        >
          waiting for more spans…
        </Box>
      </Box>
      <Box sx={{ position: 'relative', height: '100%', mx: 1.5 }}>
        <Box
          sx={{
            position: 'absolute',
            top: '50%',
            transform: 'translateY(-50%)',
            height: 13,
            borderRadius: '4px',
            left: `${left}%`,
            width: `${width}%`,
            minWidth: 3,
            background: gradients.liveTailAccessible,
            backgroundSize: '200% 100%',
            animation: 'liveShimmer 1.6s linear infinite',
            '@keyframes liveShimmer': {
              '0%': { backgroundPosition: '0% 0' },
              '100%': { backgroundPosition: '-200% 0' },
            },
          }}
        />
        <Box
          sx={{
            position: 'absolute',
            top: '50%',
            left: `${right}%`,
            transform: 'translate(-50%, -50%)',
            width: 9,
            height: 9,
            borderRadius: '50%',
            bgcolor: auroraColors.cyanBrightText,
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
            position: 'absolute',
            top: '50%',
            left: `calc(${right}% - 6px)`,
            transform: 'translate(-100%, -50%)',
            typography: 'mono',
            fontSize: 10,
            fontWeight: 700,
            color: neutralColors.white,
            whiteSpace: 'nowrap',
            pointerEvents: 'none',
          }}
        >
          running…
        </Box>
      </Box>
    </Box>
  );
};

export default LiveTailRow;
