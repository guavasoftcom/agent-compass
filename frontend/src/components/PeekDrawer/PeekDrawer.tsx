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
import { Drawer, alpha } from '@mui/material';
import { neutralColors } from '../../theme/colors';
import { backdropGradient } from '../../theme/theme';

// Slide timing shared with SessionDetailDrawer, so the peek drawers feel like one family.
const SLIDE_DURATION_MS = 260;
const SLIDE_EASING = 'cubic-bezier(.22,.8,.24,1)';

export interface PeekDrawerProps {
  open: boolean;
  onClose: () => void;
  /** Fires once the panel has fully slid away — the moment a caller may drop what it kept rendered. */
  onExited?: () => void;
  children: ReactNode;
}

/**
 * The right-hand "quick peek" slide-over chrome: a 560px (max 94vw) panel over a scrim, carrying
 * the aurora backdrop glow, a hairline left border and a heavy shadow. It owns the chrome only —
 * header, stat row, scrolling body and footer are the caller's, laid out as a flex column.
 *
 * Used by the Metrics exemplar-trace drawer and the Usage Calendar day drawer; both are peeks
 * ("click a point, glance, close, click the next") rather than navigations.
 */
const PeekDrawer = ({ open, onClose, onExited, children }: PeekDrawerProps) => (
  <Drawer
    anchor="right"
    open={open}
    onClose={onClose}
    transitionDuration={SLIDE_DURATION_MS}
    slotProps={{
      transition: {
        easing: SLIDE_EASING,
        onExited,
      },
      backdrop: {
        sx: {
          bgcolor: (t) => alpha(neutralColors.shadowDeep, t.palette.mode === 'dark' ? 0.6 : 0.45),
        },
      },
      paper: {
        sx: {
          width: 560,
          maxWidth: '94vw',
          display: 'flex',
          flexDirection: 'column',
          borderRadius: 0,
          borderLeft: 1,
          borderColor: 'divider',
          bgcolor: 'background.default',
          // The aurora glow is painted on <body> and fixed, so a panel above it would read as a flat slab.
          backgroundImage: (t) => backdropGradient(t.palette.mode),
          backgroundRepeat: 'no-repeat',
          boxShadow: (t) =>
            `-28px 0 60px ${alpha(
              t.palette.mode === 'dark' ? neutralColors.black : neutralColors.shadowIndigo,
              t.palette.mode === 'dark' ? 0.5 : 0.3,
            )}`,
        },
      },
    }}
  >
    {children}
  </Drawer>
);

export default PeekDrawer;
