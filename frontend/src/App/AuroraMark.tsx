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
import { alpha, Box } from '@mui/material';
import { auroraColors, gradients, neutralColors } from '../theme/colors';
import { fontFamilies } from '../theme/typography';

export interface AuroraMarkProps {
  size?: number;
}

/**
 * Aurora brand mark: a rounded square with a violet→pink gradient and a bold "A".
 * Replaces the compass circuit glyph in the Aurora retheme.
 */
const AuroraMark = ({ size = 36 }: AuroraMarkProps) => {
  return (
    <Box
      sx={{
        width: size,
        height: size,
        borderRadius: `${Math.round(size * 0.3)}px`,
        display: 'grid',
        placeItems: 'center',
        flexShrink: 0,
        color: neutralColors.white,
        fontFamily: fontFamilies.display,
        fontWeight: 800,
        fontSize: Math.round(size * 0.5),
        lineHeight: 1,
        background: gradients.auroraAction,
        boxShadow: `0 6px 18px ${alpha(auroraColors.violetLight, 0.45)}`,
      }}
    >
      A
    </Box>
  );
};

export default AuroraMark;
