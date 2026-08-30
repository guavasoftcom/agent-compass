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
import { useState } from 'react';
import {
  alpha,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Stack,
  TextField,
  Typography,
  useTheme,
} from '@mui/material';
import { fontFamilies } from '../../../../theme/typography';
import { colorForIndex } from '../../../../theme/theme';
import {
  formatBytes,
  formatCompact,
  formatTimestamp,
} from '../../../../lib/format';
import { PURGE_CONFIRMATION_PHRASE } from '../../settingsApi';
import type { PurgePreview } from '../../settingsTypes';

const WarningTriangleIcon = () => (
  <svg
    width={19}
    height={19}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={2}
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M12 9v4M12 17h.01" />
    <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
  </svg>
);

const CloseIcon = () => (
  <svg
    width={18}
    height={18}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={2}
    strokeLinecap="round"
  >
    <path d="M18 6L6 18M6 6l12 12" />
  </svg>
);

export interface PurgeConfirmDialogProps {
  open: boolean;
  purgePreview: PurgePreview | null;
  retentionDays: number;
  isPurging: boolean;
  purgeError: Error | null;
  onConfirm: () => void;
  onClose: () => void;
}

/**
 * Type-to-confirm dialog for the purge.
 *
 * The friction is the point. This is the only irreversible action in the app, so
 * the dialog restates exactly what will go — per table, with the cutoff spelled
 * out in absolute time rather than "30 days" — before the button can be pressed,
 * and requires the operator to type the confirmation phrase. Closing is blocked
 * while the request is in flight: a purge of millions of rows takes minutes, and
 * a dialog that vanished mid-delete would leave no indication it was still
 * running. Passing no `onClose` while purging is what blocks it — MUI 9 has no
 * `disableEscapeKeyDown`, and an absent handler suppresses both escape and
 * backdrop dismissal.
 *
 * Mounted only while open (see `PurgeDryRunCard`), so the typed phrase resets
 * itself on each opening rather than needing an effect to clear it — a previous
 * confirmation must never carry over and pre-arm the button for a window the
 * operator hasn't reviewed.
 */
const PurgeConfirmDialog = ({
  open,
  purgePreview,
  retentionDays,
  isPurging,
  purgeError,
  onConfirm,
  onClose,
}: PurgeConfirmDialogProps) => {
  const theme = useTheme();
  const [typedConfirmation, setTypedConfirmation] = useState('');

  const isConfirmed = typedConfirmation.trim() === PURGE_CONFIRMATION_PHRASE;
  const hasRowsToDelete = (purgePreview?.totalRowsToDelete ?? 0) > 0;
  const canPurge =
    isConfirmed && hasRowsToDelete && !isPurging && purgePreview !== null;

  const preservedRows =
    purgePreview?.tables.reduce((sum, table) => sum + table.preservedRows, 0) ??
    0;

  return (
    <Dialog
      open={open}
      onClose={isPurging ? undefined : onClose}
      maxWidth="md"
      fullWidth
    >
      <DialogTitle
        sx={{
          position: 'relative',
          display: 'flex',
          alignItems: 'flex-start',
          gap: 1.75,
          pr: 6,
        }}
      >
        <Box
          sx={{
            width: 38,
            height: 38,
            flexShrink: 0,
            borderRadius: '11px',
            display: 'grid',
            placeItems: 'center',
            color: 'error.main',
            bgcolor: alpha(theme.palette.error.main, 0.14),
          }}
        >
          <WarningTriangleIcon />
        </Box>
        <Box
          component="span"
          sx={{ fontFamily: fontFamilies.display, fontWeight: 700, pt: 0.5 }}
        >
          Permanently delete sessions dormant for {retentionDays}+ days?
        </Box>
        <IconButton
          onClick={isPurging ? undefined : onClose}
          disabled={isPurging}
          size="small"
          aria-label="Close"
          sx={{ position: 'absolute', top: 14, right: 14 }}
        >
          <CloseIcon />
        </IconButton>
      </DialogTitle>

      <DialogContent dividers>
        {!purgePreview ? (
          <Typography color="text.secondary">No estimate available.</Typography>
        ) : (
          <Stack spacing={2}>
            <Typography variant="body2">
              This deletes every session with no activity anywhere — logs,
              tokens, or traces — since{' '}
              <Box component="b">{formatTimestamp(purgePreview.cutoff)}</Box>,
              from all three telemetry tables. A session with any activity since
              then is left untouched, including its oldest rows.{' '}
              <Box component="b">It cannot be undone.</Box>
            </Typography>

            <Box
              sx={{
                border: 1,
                borderColor: 'divider',
                borderRadius: 1.5,
                overflow: 'hidden',
              }}
            >
              {purgePreview.tables.map((table, index) => (
                <Stack
                  key={table.tableName}
                  direction="row"
                  sx={{
                    justifyContent: 'space-between',
                    alignItems: 'baseline',
                    px: 1.75,
                    py: 1.1,
                    borderBottom: 1,
                    borderColor: 'divider',
                    '&:last-of-type': { borderBottom: 0 },
                  }}
                >
                  <Stack
                    direction="row"
                    spacing={1}
                    sx={{ alignItems: 'center' }}
                  >
                    <Box
                      component="span"
                      sx={{
                        width: 8,
                        height: 8,
                        borderRadius: '50%',
                        flexShrink: 0,
                        bgcolor: colorForIndex(index),
                      }}
                    />
                    <Box
                      component="span"
                      sx={{ typography: 'mono', fontSize: 13 }}
                    >
                      {table.tableName}
                    </Box>
                  </Stack>
                  <Box
                    component="span"
                    sx={{ fontVariantNumeric: 'tabular-nums', fontSize: 13.5 }}
                  >
                    <Box component="b">{formatCompact(table.rowsToDelete)}</Box>
                    <Box component="span" sx={{ color: 'text.secondary' }}>
                      {` of ${formatCompact(table.totalRows)} · ~${formatBytes(table.estimatedReclaimableBytes)}`}
                    </Box>
                  </Box>
                </Stack>
              ))}
            </Box>

            <Box sx={{ p: 1.5, borderRadius: 1.5, bgcolor: 'action.hover' }}>
              <Typography
                variant="body2"
                sx={{ fontVariantNumeric: 'tabular-nums' }}
              >
                Total:{' '}
                <Box component="b">
                  {formatCompact(purgePreview.totalRowsToDelete)}
                </Box>{' '}
                rows, about{' '}
                <Box component="b">
                  {formatBytes(purgePreview.estimatedReclaimableBytes)}
                </Box>
                .
              </Typography>
            </Box>

            <Box
              sx={{
                p: 1.75,
                borderRadius: 1.5,
                border: 1,
                borderColor: alpha(theme.palette.warning.main, 0.35),
                bgcolor: alpha(theme.palette.warning.main, 0.08),
              }}
            >
              <Typography
                variant="caption"
                sx={{
                  fontWeight: 700,
                  color: 'warning.main',
                  display: 'block',
                  mb: 0.75,
                }}
              >
                Run this while no agent is exporting
              </Typography>
              <Box
                component="ul"
                sx={{ m: 0, pl: 2.25, typography: 'body2', lineHeight: 1.6 }}
              >
                <li>
                  A session is only deleted once it has no activity anywhere —
                  logs, tokens, or traces — for the whole window above. A
                  session with any recent activity is left completely untouched,
                  including its oldest rows: sessions are never split between
                  &quot;old, deleted&quot; and &quot;recent, kept&quot;.
                </li>
                <li>
                  The delete holds locks on all three tables for as long as it
                  runs — on a large database, minutes. Telemetry arriving
                  meanwhile can block behind it, and an exporter that gives up
                  drops those events for good.
                </li>
                <li>
                  Every other page will shift underneath anyone reading it,
                  mid-session.
                </li>
                <li>
                  {preservedRows > 0 ? (
                    <>
                      {formatCompact(preservedRows)} rows are kept despite being
                      older than the cutoff — almost all of them because their
                      session is still active elsewhere, plus one marker per
                      counter inside a purged session as a safeguard: if that
                      session ever emitted again, its next value wouldn&apos;t
                      be booked as one giant spike.
                    </>
                  ) : (
                    <>
                      Inside any session that does get purged, the newest row of
                      every metric counter is kept regardless of age, as a
                      safeguard: if that session ever emitted again, its next
                      value wouldn&apos;t be booked as one giant spike.
                    </>
                  )}
                </li>
                <li>
                  Space is marked reusable, not returned to the disk. That needs
                  a separate{' '}
                  <Box component="code" sx={{ typography: 'mono' }}>
                    VACUUM FULL
                  </Box>
                  .
                </li>
                <li>
                  This is independent of Claude Code&apos;s own{' '}
                  <Box component="code" sx={{ typography: 'mono' }}>
                    cleanupPeriodDays
                  </Box>{' '}
                  setting (default 30 days), which controls how long a session
                  stays resumable locally. Purge more aggressively than that and
                  a resumed session can show up with no history — its telemetry
                  was already gone.
                </li>
              </Box>
            </Box>

            {purgeError && (
              <Typography variant="body2" sx={{ color: 'error.main' }}>
                {purgeError.message}
              </Typography>
            )}

            <Box>
              <Typography variant="body2" sx={{ mb: 0.75 }}>
                Type{' '}
                <Box component="b" sx={{ typography: 'mono' }}>
                  {PURGE_CONFIRMATION_PHRASE}
                </Box>{' '}
                to confirm.
              </Typography>
              <TextField
                value={typedConfirmation}
                onChange={(event) => setTypedConfirmation(event.target.value)}
                placeholder={PURGE_CONFIRMATION_PHRASE}
                size="small"
                fullWidth
                autoComplete="off"
                disabled={isPurging}
                slotProps={{
                  htmlInput: { 'aria-label': 'Purge confirmation phrase' },
                }}
              />
            </Box>
          </Stack>
        )}
      </DialogContent>

      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} disabled={isPurging} color="inherit">
          Cancel
        </Button>
        <Button
          onClick={onConfirm}
          disabled={!canPurge}
          color="error"
          variant="contained"
          startIcon={
            isPurging ? (
              <CircularProgress size={16} color="inherit" />
            ) : undefined
          }
        >
          {isPurging ? 'Purging…' : 'Delete permanently'}
        </Button>
      </DialogActions>
    </Dialog>
  );
};

export default PurgeConfirmDialog;
