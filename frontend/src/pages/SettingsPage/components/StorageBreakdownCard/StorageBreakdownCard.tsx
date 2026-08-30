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
import { useMemo } from 'react';
import { Box, Paper, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from '@mui/material';
import BreakdownList from '../../../../components/BreakdownList';
import { fontFamilies } from '../../../../theme/typography';
import { formatBytes, formatCompact, formatRelativeTime } from '../../../../lib/format';
import { retentionSpanDays, storageSharePercent } from '../../settingsDerivations';
import type { StorageOverview } from '../../settingsTypes';

export interface StorageBreakdownCardProps {
  storage: StorageOverview | null;
  isStorageLoading: boolean;
}

// Sizes read as one token ("1.6 GB"), so they must never break across lines —
// the card is narrow enough at md that the default wrapping split them.
const numberCell = {
  textAlign: 'right' as const,
  fontVariantNumeric: 'tabular-nums',
  whiteSpace: 'nowrap' as const,
};

/**
 * Per-table footprint: a ranked share list beside the exact decomposition.
 *
 * The TOAST column is the one worth reading — on real data `log_records` keeps
 * ~86% of its bytes there, because each row's attribute payload averages around
 * 11 KB. A table whose TOAST dwarfs its heap is why the Logs page filters on
 * generated columns instead of extracting from the jsonb per row.
 */
const StorageBreakdownCard = ({ storage, isStorageLoading }: StorageBreakdownCardProps) => {
  const shareRows = useMemo(() => {
    if (!storage) {
      return [];
    }
    return storage.tables.map((table, index) => ({
      label: table.tableName,
      value: formatBytes(table.totalBytes),
      percentage: storageSharePercent(table, storage.databaseTotalBytes),
      colorIndex: index,
    }));
  }, [storage]);

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Typography sx={{ fontFamily: fontFamilies.display, fontWeight: 600, fontSize: 16 }}>
        Storage by table
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75, mb: 1.5, lineHeight: 1.5 }}>
        Sizes decompose exactly into heap, indexes, and TOAST — the out-of-line store for large
        values. Row counts are exact, not planner estimates. Growth is projected from average row
        size and the last seven days of inserts, so it assumes row size stays put.
      </Typography>

      {!storage && isStorageLoading ? (
        <Typography color="text.secondary">Measuring…</Typography>
      ) : !storage || storage.tables.length === 0 ? (
        <Typography color="text.secondary">No tables reported.</Typography>
      ) : (
        <>
          <BreakdownList rows={shareRows} layout="stacked" showRank />

          <Box sx={{ overflowX: 'auto', mt: 2 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Table</TableCell>
                  <TableCell sx={numberCell}>Rows</TableCell>
                  <TableCell sx={numberCell}>Heap</TableCell>
                  <TableCell sx={numberCell}>Indexes</TableCell>
                  <TableCell sx={numberCell}>TOAST</TableCell>
                  <TableCell sx={numberCell}>Total</TableCell>
                  <TableCell sx={numberCell}>Growth/day</TableCell>
                  <TableCell sx={numberCell}>History</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {storage.tables.map((table) => {
                  const spanDays = retentionSpanDays(table);
                  return (
                    <TableRow key={table.tableName} hover>
                      <TableCell sx={{ typography: 'mono', fontWeight: 600 }}>
                        {table.tableName}
                      </TableCell>
                      <TableCell sx={numberCell}>{formatCompact(table.rowCount)}</TableCell>
                      <TableCell sx={{ ...numberCell, color: 'text.secondary' }}>
                        {formatBytes(table.heapBytes)}
                      </TableCell>
                      <TableCell sx={{ ...numberCell, color: 'text.secondary' }}>
                        {formatBytes(table.indexBytes)}
                      </TableCell>
                      <TableCell sx={numberCell}>{formatBytes(table.toastBytes)}</TableCell>
                      <TableCell sx={{ ...numberCell, fontWeight: 700 }}>
                        {formatBytes(table.totalBytes)}
                      </TableCell>
                      <TableCell sx={numberCell}>
                        {table.estimatedBytesPerDay > 0
                          ? formatBytes(table.estimatedBytesPerDay)
                          : <Box component="span" sx={{ color: 'text.disabled' }}>—</Box>}
                      </TableCell>
                      <TableCell sx={numberCell}>
                        {spanDays === null ? (
                          <Box component="span" sx={{ color: 'text.disabled' }}>—</Box>
                        ) : (
                          <Tooltip
                            arrow
                            title={`Oldest row ${formatRelativeTime(table.oldestTimestamp ?? '')}`}
                          >
                            <Box component="span" sx={{ cursor: 'help' }}>{`${spanDays}d`}</Box>
                          </Tooltip>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </Box>
        </>
      )}
    </Paper>
  );
};

export default StorageBreakdownCard;
