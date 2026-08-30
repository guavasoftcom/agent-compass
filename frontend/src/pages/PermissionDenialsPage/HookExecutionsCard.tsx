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
import {
  alpha,
  Box,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
  useTheme,
} from '@mui/material';
import type { HookExecutionRow } from '../../api';
import { fontFamilies } from '../../theme/typography';

export interface HookExecutionsCardProps {
  hookRows: HookExecutionRow[];
  isHooksLoading: boolean;
}

/**
 * "Hook execution outcomes" — Aurora-styled table. Blocking errors (hooks that
 * actively prevented a tool from running) read as a red chip; a clean count is a
 * muted zero. OK counts use the success colour. Mirrors the mockup.
 */
const HookExecutionsCard = ({ hookRows, isHooksLoading }: HookExecutionsCardProps) => {
  const theme = useTheme();

  const numCell = { textAlign: 'right' as const, fontVariantNumeric: 'tabular-nums' };

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Typography sx={{ fontFamily: fontFamilies.display, fontWeight: 600, fontSize: 16 }}>
        Hook execution outcomes
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75, mb: 0.5, lineHeight: 1.5 }}>
        <Box component="b" sx={{ color: 'error.main', fontWeight: 700 }}>Blocking errors</Box>{' '}
        are hooks that actively prevented tool execution. Non-blocking errors are logged failures that
        did not stop the tool from running.
      </Typography>

      {hookRows.length === 0 && !isHooksLoading ? (
        <Typography color="text.secondary" sx={{ mt: 1.5 }}>
          No hook executions recorded in this window.
        </Typography>
      ) : (
        <Table size="small" sx={{ mt: 1 }}>
          <TableHead>
            <TableRow>
              <TableCell>Event</TableCell>
              <TableCell>Hook</TableCell>
              <TableCell sx={numCell}>Total</TableCell>
              <TableCell sx={numCell}>OK</TableCell>
              <TableCell sx={numCell}>Blocking</TableCell>
              <TableCell sx={numCell}>Non-blocking</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {hookRows.map((row) => {
              const blocking = row.blockingErrors;
              return (
                <TableRow key={`${row.hookEvent}::${row.hookName}`} hover>
                  <TableCell sx={{ fontWeight: 600 }}>{row.hookEvent}</TableCell>
                  <TableCell
                    sx={{
                      typography: 'mono',
                      maxWidth: 300,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                      color: 'text.secondary',
                    }}
                  >
                    {row.hookName}
                  </TableCell>
                  <TableCell sx={{ ...numCell, fontWeight: 700 }}>{row.total.toLocaleString()}</TableCell>
                  <TableCell sx={{ ...numCell, color: 'success.main' }}>{row.successes.toLocaleString()}</TableCell>
                  <TableCell sx={numCell}>
                    {blocking > 0 ? (
                      <Box
                        component="span"
                        sx={{
                          display: 'inline-flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          minWidth: 26,
                          height: 24,
                          px: 1,
                          borderRadius: '7px',
                          fontWeight: 700,
                          fontSize: 13,
                          fontVariantNumeric: 'tabular-nums',
                          color: 'error.main',
                          bgcolor: alpha(theme.palette.error.main, 0.16),
                        }}
                      >
                        {blocking.toLocaleString()}
                      </Box>
                    ) : (
                      <Box component="span" sx={{ color: 'text.disabled' }}>0</Box>
                    )}
                  </TableCell>
                  <TableCell sx={numCell}>{row.nonBlockingErrors.toLocaleString()}</TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
    </Paper>
  );
};

export default HookExecutionsCard;
