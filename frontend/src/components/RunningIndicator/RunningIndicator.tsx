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
import { Box, Tooltip, alpha } from '@mui/material';

// Marks a row/card/panel the backend reports as still in progress (a session's
// running turn, a session row, a trace, ...). Its owning figures are partial
// until the backend flips the flag back to false — the caller is responsible
// for polling while this is showing so they fill in without a manual reload.
// Design handoff (Aurora Sessions mockup): a small pulsing dot, not a
// spinner+text chip — an earlier revision of this component. Shared across
// pages (Sessions' turn cards and grid rows, Traces' table/stream/summary/detail)
// so every "this is still running" indicator in the app is the same dot with a
// caller-supplied tooltip/aria-label, not a hand-rolled copy per page. The
// pulse keyframes are inlined in `sx` rather than pulled from a shared
// animation module, matching `LiveTailToggle`'s identical pattern (the Traces
// page's own "is something live happening" dot) rather than introducing a
// second way to write a CSS animation in this codebase.
export interface RunningIndicatorProps {
  tooltip?: string;
  ariaLabel?: string;
}

export const RunningIndicator = ({
  tooltip = 'This turn is still running. It updates automatically.',
  ariaLabel = 'Prompt still running',
}: RunningIndicatorProps) => (
  <Tooltip title={tooltip} placement="top" arrow>
    <Box
      component="span"
      role="status"
      aria-label={ariaLabel}
      sx={{
        display: 'inline-flex',
        width: 8,
        height: 8,
        flexShrink: 0,
        borderRadius: '50%',
        bgcolor: 'primary.main',
        boxShadow: (t) => `0 0 0 3px ${alpha(t.palette.primary.main, 0.32)}`,
        cursor: 'help',
        animation: 'sessionRunningPulse 1.6s ease-in-out infinite',
        '@keyframes sessionRunningPulse': {
          '0%, 100%': { opacity: 1, transform: 'scale(1)' },
          '50%': { opacity: 0.45, transform: 'scale(0.82)' },
        },
      }}
    />
  </Tooltip>
);

export default RunningIndicator;
