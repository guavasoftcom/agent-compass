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
import type { SxProps, Theme } from '@mui/material';
import { alpha, Box } from '@mui/material';
import { fontFamilies } from '../../theme/typography';
import { radii } from '../../theme/theme';

// 'primary'/'info' added for the Analyze trace dialog's Regenerate/Copy pair —
// a plain-bordered button reads fine for a toolbar full of equal-weight
// actions, but a dialog's primary action (Regenerate) and its secondary
// utility action (Copy) needed to look like different things, not two
// identical gray buttons.
export type GhostButtonTone = 'default' | 'danger' | 'primary' | 'info';

export interface GhostButtonProps {
  children: ReactNode;
  onClick?: () => void;
  /** 'default' = bordered paper button; tinted variants borrow the palette color named. */
  tone?: GhostButtonTone;
  disabled?: boolean;
  type?: 'button' | 'submit' | 'reset';
  title?: string;
  /** Merged after the base styles — override size/shape here (e.g. an icon square). */
  sx?: SxProps<Theme>;
}

const TONE_PALETTE_KEY: Record<Exclude<GhostButtonTone, 'default'>, 'error' | 'primary' | 'info'> = {
  danger: 'error',
  primary: 'primary',
  info: 'info',
};

// Outlined "ghost" action button shared across toolbars and pagers: a bordered,
// paper-backed button whose label brightens on hover. Disabled dims it and
// suppresses the hover. Pass sx to retune size/shape per call site.
const GhostButton = ({
  children,
  onClick,
  tone = 'default',
  disabled = false,
  type = 'button',
  title,
  sx,
}: GhostButtonProps) => {
  const isTinted = tone !== 'default';
  const paletteKey = isTinted ? TONE_PALETTE_KEY[tone] : null;
  const isBold = tone === 'danger' || tone === 'primary';
  return (
    <Box
      component="button"
      type={type}
      title={title}
      disabled={disabled}
      onClick={onClick}
      sx={[
        {
          display: 'inline-flex',
          alignItems: 'center',
          gap: 0.9,
          height: 30,
          px: 1.4,
          borderRadius: radii.sm,
          border: 1,
          cursor: disabled ? 'default' : 'pointer',
          opacity: disabled ? 0.4 : 1,
          fontFamily: fontFamilies.display,
          fontSize: 12,
          fontWeight: isBold ? 700 : 600,
          color: paletteKey ? (paletteKey + '.main') : 'text.secondary',
          borderColor: paletteKey
            ? (t) => alpha(t.palette[paletteKey].main, 0.4)
            : 'divider',
          bgcolor: paletteKey
            ? (t) => alpha(t.palette[paletteKey].main, 0.12)
            : 'background.paper',
          '& svg': { fontSize: 14 },
          '&:hover': disabled
            ? {}
            : { color: paletteKey ? (paletteKey + '.main') : 'text.primary' },
        },
        ...(Array.isArray(sx) ? sx : [sx]),
      ]}
    >
      {children}
    </Box>
  );
};

export default GhostButton;
