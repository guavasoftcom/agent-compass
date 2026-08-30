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
import { Box, alpha, useTheme } from '@mui/material';
import { type Severity } from '../../logsApi';
import { severityColor } from '../severity';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';

// Severity badge (e.g. ERROR / WARN / INFO / DEBUG) used by both the Logs stream
// rows and the Logs table. Lives here rather than inside either so neither has to
// import from the other.
export const SeverityChip = ({ severity }: { severity: Severity }) => {
  const theme = useTheme();
  const c = severityColor(theme, severity);
  const isDebug = severity === 'DEBUG';
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        height: 19,
        px: 1,
        borderRadius: radii.xs,
        fontFamily: fontFamilies.display,
        fontSize: 10,
        fontWeight: 700,
        letterSpacing: '0.5px',
        color: isDebug ? 'text.secondary' : c,
        bgcolor: isDebug ? 'action.hover' : alpha(c, 0.15),
      }}
    >
      {severity}
    </Box>
  );
};
