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

export type GhostButtonTone = 'default' | 'danger';

export interface GhostButtonProps {
  children: ReactNode;
  onClick?: () => void;
  /** `default` = bordered paper button; `danger` = error-tinted variant. */
  tone?: GhostButtonTone;
  disabled?: boolean;
  type?: 'button' | 'submit' | 'reset';
  title?: string;
  /** Merged after the base styles — override size/shape here (e.g. an icon square). */
  sx?: SxProps<Theme>;
}

// Outlined "ghost" action button shared across toolbars and pagers: a bordered,
// paper-backed button whose label brightens on hover. Disabled dims it and
// suppresses the hover. Pass `sx` to retune size/shape per call site.
const GhostButton = ({
  children,
  onClick,
  tone = 'default',
  disabled = false,
  type = 'button',
  title,
  sx,
}: GhostButtonProps) => {
  const isDanger = tone === 'danger';
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
          fontWeight: isDanger ? 700 : 600,
          color: isDanger ? 'error.main' : 'text.secondary',
          borderColor: isDanger
            ? (t) => alpha(t.palette.error.main, 0.4)
            : 'divider',
          bgcolor: isDanger
            ? (t) => alpha(t.palette.error.main, 0.12)
            : 'background.paper',
          '& svg': { fontSize: 14 },
          '&:hover': disabled
            ? {}
            : { color: isDanger ? 'error.main' : 'text.primary' },
        },
        ...(Array.isArray(sx) ? sx : [sx]),
      ]}
    >
      {children}
    </Box>
  );
};

export default GhostButton;
