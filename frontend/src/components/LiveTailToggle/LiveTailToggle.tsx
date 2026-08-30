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
import { auroraColors, gradients } from '../../theme/colors';
import { fontFamilies } from '../../theme/typography';
import { radii } from '../../theme/theme';

export interface LiveTailToggleProps {
  active: boolean;
  locked: boolean;
  tooltip?: string;
  onToggle: () => void;
}

const LiveTailToggle = ({ active, locked, tooltip, onToggle }: LiveTailToggleProps) => {
  return (
    <Box
      component="button"
      title={tooltip}
      onClick={() => {
        if (!locked) {
          onToggle();
        }
      }}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        height: 40,
        px: 1.75,
        borderRadius: radii.lg,
        cursor: locked ? 'not-allowed' : 'pointer',
        opacity: locked ? 0.4 : 1,
        fontFamily: fontFamilies.display,
        fontSize: 13,
        fontWeight: 600,
        border: active ? 'none' : 1,
        borderColor: 'divider',
        color: active ? 'common.white' : 'text.secondary',
        background: active
          ? gradients.liveTail
          : (t) => t.palette.background.paper,
        boxShadow: active ? `0 6px 16px ${alpha(auroraColors.greenDeep, 0.4)}` : 'none',
        '&:hover': { color: active ? 'common.white' : 'text.primary' },
      }}
    >
      <Box
        sx={{
          width: 9,
          height: 9,
          borderRadius: '50%',
          bgcolor: active ? 'common.white' : 'text.disabled',
          animation: active ? 'tailPulse 1.1s infinite' : 'none',
          '@keyframes tailPulse': {
            '0%,100%': { opacity: 1, transform: 'scale(1)' },
            '50%': { opacity: 0.35, transform: 'scale(0.8)' },
          },
        }}
      />
      {active ? 'LIVE' : 'Live tail'}
    </Box>
  );
};

export default LiveTailToggle;
