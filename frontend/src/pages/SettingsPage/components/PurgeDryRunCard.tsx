import { useCallback, useEffect, useState } from 'react';
import { alpha, Box, Paper, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography, useTheme } from '@mui/material';
import GhostButton from '../../../components/GhostButton';
import SegmentedToggle from '../../../components/SegmentedToggle';
import { fontFamilies } from '../../../theme/typography';
import { formatBytes, formatCompact, formatTimestamp } from '../../../lib/format';
import PurgeConfirmDialog from './PurgeConfirmDialog';
import type { PurgePreview, PurgeResult } from '../settingsTypes';

export interface PurgeDryRunCardProps {
  purgePreview: PurgePreview | null;
  isPurgePreviewLoading: boolean;
  retentionDays: number;
  onRetentionDaysChange: (retentionDays: number) => void;
  isPurgeDialogOpen: boolean;
  onOpenPurgeDialog: () => void;
  onClosePurgeDialog: () => void;
  onConfirmPurge: () => void;
  isPurging: boolean;
  purgeError: Error | null;
  purgeResult: PurgeResult | null;
}

const RETENTION_OPTIONS = [30, 60, 90, 180].map((days) => ({ value: days, label: `${days}d` }));

/** metric_points, log_records, spans — the three telemetry tables a purge ever touches. */
const SKELETON_TABLE_ROW_COUNT = 3;

/** How long the copy button stays in its confirmed state. */
const COPIED_TIMEOUT_MS = 1600;

const MILLISECONDS_PER_SECOND = 1000;

const numberCell = { textAlign: 'right' as const, fontVariantNumeric: 'tabular-nums' };

/**
 * Retention: preview, copyable SQL, and the one action in this app that deletes data.
 *
 * Nothing prunes old telemetry on its own — no TTL, no cleanup job, no schedule. This
 * card measures what a cutoff would remove, and the operator either copies the SQL to
 * run themselves or presses Purge and confirms in `PurgeConfirmDialog`.
 *
 * The purge is gated on whole sessions, not row age: `session.id` is common to all
 * three telemetry tables, so a session with any activity anywhere since the cutoff is
 * left completely alone, including its oldest rows — a session is never split between
 * "old, deleted" and "recent, kept". Inside a session that does qualify, metric_points
 * additionally keeps the newest row of every counter regardless (see the "Kept" column
 * and `PurgeConfirmDialog`'s warning list), because `value_delta` is computed against a
 * row's predecessor and losing it would spike that counter's next emission.
 */
const PurgeDryRunCard = ({
  purgePreview,
  isPurgePreviewLoading,
  retentionDays,
  onRetentionDaysChange,
  isPurgeDialogOpen,
  onOpenPurgeDialog,
  onClosePurgeDialog,
  onConfirmPurge,
  isPurging,
  purgeError,
  purgeResult,
}: PurgeDryRunCardProps) => {
  const theme = useTheme();
  const [hasCopied, setHasCopied] = useState(false);

  useEffect(() => {
    if (!hasCopied) {
      return undefined;
    }
    const timeoutId = window.setTimeout(() => setHasCopied(false), COPIED_TIMEOUT_MS);
    return () => window.clearTimeout(timeoutId);
  }, [hasCopied]);

  const handleCopy = useCallback(() => {
    if (!purgePreview) {
      return;
    }
    void navigator.clipboard.writeText(purgePreview.sql).then(() => setHasCopied(true));
  }, [purgePreview]);

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between' }}
      >
        <Box>
          <Typography sx={{ fontFamily: fontFamilies.display, fontWeight: 600, fontSize: 16 }}>
            Retention
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75, lineHeight: 1.5 }}>
            Nothing prunes old telemetry on its own. Pick a window to see what removing everything
            older would reclaim, then either run the SQL yourself or purge from here — the button
            deletes permanently, behind a confirmation.
          </Typography>
        </Box>
        <SegmentedToggle
          options={RETENTION_OPTIONS}
          value={retentionDays}
          onChange={onRetentionDaysChange}
        />
      </Stack>

      {!purgePreview && isPurgePreviewLoading ? (
        <PurgeDryRunSkeleton />
      ) : !purgePreview ? (
        <Typography color="text.secondary" sx={{ mt: 2 }}>No estimate available.</Typography>
      ) : (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2, mb: 1 }}>
            Rows older than{' '}
            <Box component="b" sx={{ color: 'text.primary' }}>
              {formatTimestamp(purgePreview.cutoff)}
            </Box>
            {' — '}
            <Box component="b" sx={{ color: 'text.primary' }}>
              {formatCompact(purgePreview.totalRowsToDelete)}
            </Box>
            {' rows, about '}
            <Box component="b" sx={{ color: 'text.primary' }}>
              {formatBytes(purgePreview.estimatedReclaimableBytes)}
            </Box>
            .
          </Typography>

          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Table</TableCell>
                  <TableCell>Cut on</TableCell>
                  <TableCell sx={numberCell}>Rows to delete</TableCell>
                  <TableCell sx={numberCell}>Of total</TableCell>
                  <TableCell sx={numberCell}>Kept</TableCell>
                  <TableCell sx={numberCell}>Share</TableCell>
                  <TableCell sx={numberCell}>Est. reclaimed</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {purgePreview.tables.map((table) => (
                  <TableRow key={table.tableName} hover>
                    <TableCell sx={{ typography: 'mono', fontWeight: 600 }}>
                      {table.tableName}
                    </TableCell>
                    <TableCell sx={{ typography: 'mono', color: 'text.secondary', fontSize: 12.5 }}>
                      {table.timestampColumn}
                    </TableCell>
                    <TableCell sx={{ ...numberCell, fontWeight: 700 }}>
                      {formatCompact(table.rowsToDelete)}
                    </TableCell>
                    <TableCell sx={{ ...numberCell, color: 'text.secondary' }}>
                      {formatCompact(table.totalRows)}
                    </TableCell>
                    <TableCell sx={numberCell}>
                      {table.preservedRows > 0 ? (
                        <Tooltip
                          arrow
                          title={
                            'Older than the cutoff but kept anyway — almost always because the row ' +
                            "belongs to a session that's still active in another signal. A session " +
                            'is always purged whole, never split between old and recent rows.'
                          }
                        >
                          <Box component="span" sx={{ cursor: 'help', color: 'info.main' }}>
                            {formatCompact(table.preservedRows)}
                          </Box>
                        </Tooltip>
                      ) : (
                        <Box component="span" sx={{ color: 'text.disabled' }}>—</Box>
                      )}
                    </TableCell>
                    <TableCell sx={numberCell}>{`${table.sharePercent.toFixed(1)}%`}</TableCell>
                    <TableCell sx={numberCell}>
                      {formatBytes(table.estimatedReclaimableBytes)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>

          <Box
            sx={{
              mt: 2,
              p: 1.75,
              borderRadius: 1.5,
              border: 1,
              borderColor: alpha(theme.palette.warning.main, 0.35),
              bgcolor: alpha(theme.palette.warning.main, 0.08),
            }}
          >
            <Typography
              variant="caption"
              sx={{ fontWeight: 700, color: 'warning.main', display: 'block', mb: 0.75 }}
            >
              Before you run this
            </Typography>
            <Box component="ul" sx={{ m: 0, pl: 2.25, typography: 'body2', lineHeight: 1.6 }}>
              <li>
                A session is deleted only once it has no activity anywhere — logs, tokens, or
                traces — for the whole window above. A session with any recent activity keeps every
                row, including its oldest ones: sessions are never split between old and recent.
              </li>
              <li>
                A <Box component="code" sx={{ typography: 'mono' }}>DELETE</Box> makes the space
                reusable by Postgres but does not return it to the operating system. Only{' '}
                <Box component="code" sx={{ typography: 'mono' }}>VACUUM FULL</Box> shrinks the files,
                and it takes an exclusive lock plus room for a full rewrite.
              </li>
              <li>
                Inside a session that does get purged, the newest row of every metric counter is
                kept regardless of age, as a safeguard: if that session ever emitted again, its
                next value wouldn&apos;t be booked as one giant spike.
              </li>
              <li>
                This is independent of Claude Code&apos;s own{' '}
                <Box component="code" sx={{ typography: 'mono' }}>cleanupPeriodDays</Box> setting
                (default 30 days, controlling how long a session stays resumable locally) — purge
                more aggressively than that and a resumed session&apos;s history can already be gone
                from this dashboard even though the conversation itself still resumes fine.
              </li>
            </Box>
          </Box>

          <Stack
            direction="row"
            spacing={1}
            sx={{ mt: 2, alignItems: 'center', flexWrap: 'wrap', rowGap: 1 }}
          >
            <GhostButton onClick={handleCopy}>{hasCopied ? 'Copied' : 'Copy SQL'}</GhostButton>
            <GhostButton
              tone="danger"
              onClick={onOpenPurgeDialog}
              disabled={isPurging || purgePreview.totalRowsToDelete === 0}
              title={
                purgePreview.totalRowsToDelete === 0
                  ? 'Nothing is older than this cutoff'
                  : 'Opens a confirmation dialog — nothing is deleted until you type PURGE and confirm there'
              }
            >
              {isPurging ? 'Purging…' : 'Purge…'}
            </GhostButton>
            <Typography variant="caption" color="text.secondary">
              Byte figures are proportional estimates, not measurements.
            </Typography>
          </Stack>

          {purgeError && !isPurgeDialogOpen && (
            <Typography variant="body2" sx={{ mt: 1.5, color: 'error.main' }}>
              {purgeError.message}
            </Typography>
          )}

          {purgeResult && (
            <Box
              sx={{
                mt: 2,
                p: 1.75,
                borderRadius: 1.5,
                border: 1,
                borderColor: alpha(theme.palette.success.main, 0.35),
                bgcolor: alpha(theme.palette.success.main, 0.08),
              }}
            >
              <Typography
                variant="caption"
                sx={{ fontWeight: 700, color: 'success.main', display: 'block', mb: 0.75 }}
              >
                Purge complete
              </Typography>
              <Typography variant="body2" sx={{ lineHeight: 1.6 }}>
                Deleted <Box component="b">{formatCompact(purgeResult.totalRowsDeleted)}</Box> rows
                from sessions dormant for {purgeResult.retentionDays}+ days, in{' '}
                {(purgeResult.durationMillis / MILLISECONDS_PER_SECOND).toFixed(1)}s
                {purgeResult.preservedRows > 0 && (
                  <>
                    , keeping <Box component="b">{formatCompact(purgeResult.preservedRows)}</Box>{' '}
                    rows — sessions still active elsewhere, plus a marker per counter so live
                    streams stay correct
                  </>
                )}
                . Database size {formatBytes(purgeResult.databaseTotalBytesBefore)} →{' '}
                {formatBytes(purgeResult.databaseTotalBytesAfter)} — the space is now reusable by
                Postgres but still allocated on disk. To hand it back, run{' '}
                <Box component="code" sx={{ typography: 'mono' }}>
                  {purgeResult.reclaimSpaceSql}
                </Box>
              </Typography>
            </Box>
          )}

          <Box
            component="pre"
            sx={{
              mt: 1.5,
              m: 0,
              p: 1.75,
              maxHeight: 240,
              overflow: 'auto',
              borderRadius: 1.5,
              border: 1,
              borderColor: 'divider',
              bgcolor: 'action.hover',
              typography: 'mono',
              fontSize: 12,
              lineHeight: 1.6,
              whiteSpace: 'pre',
            }}
          >
            {purgePreview.sql}
          </Box>
        </>
      )}

      {/* Mounted only while open so the typed confirmation resets itself each time. */}
      {isPurgeDialogOpen && (
      <PurgeConfirmDialog
        open
        purgePreview={purgePreview}
        retentionDays={retentionDays}
        isPurging={isPurging}
        purgeError={purgeError}
        onConfirm={onConfirmPurge}
        onClose={onClosePurgeDialog}
      />
      )}
    </Paper>
  );
};

/**
 * Placeholder shown while a newly selected retention window's preview is loading.
 *
 * Mirrors the real content's structure (summary line, table, action row, SQL box) at
 * roughly the same height, so switching the retention toggle doesn't collapse the card
 * down to a single "Estimating…" line and then snap back once the estimate arrives.
 */
const PurgeDryRunSkeleton = () => (
  <>
    <Typography variant="body2" sx={{ mt: 2, mb: 1 }}>
      <Skeleton variant="text" width="60%" />
    </Typography>

    <Box sx={{ overflowX: 'auto' }}>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Table</TableCell>
            <TableCell>Cut on</TableCell>
            <TableCell sx={numberCell}>Rows to delete</TableCell>
            <TableCell sx={numberCell}>Of total</TableCell>
            <TableCell sx={numberCell}>Kept</TableCell>
            <TableCell sx={numberCell}>Share</TableCell>
            <TableCell sx={numberCell}>Est. reclaimed</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {Array.from({ length: SKELETON_TABLE_ROW_COUNT }, (_, rowIndex) => (
            <TableRow key={rowIndex}>
              <TableCell><Skeleton variant="text" width={110} /></TableCell>
              <TableCell><Skeleton variant="text" width={90} /></TableCell>
              <TableCell sx={numberCell}><Skeleton variant="text" width={56} sx={{ ml: 'auto' }} /></TableCell>
              <TableCell sx={numberCell}><Skeleton variant="text" width={56} sx={{ ml: 'auto' }} /></TableCell>
              <TableCell sx={numberCell}><Skeleton variant="text" width={32} sx={{ ml: 'auto' }} /></TableCell>
              <TableCell sx={numberCell}><Skeleton variant="text" width={44} sx={{ ml: 'auto' }} /></TableCell>
              <TableCell sx={numberCell}><Skeleton variant="text" width={64} sx={{ ml: 'auto' }} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>

    <Skeleton variant="rounded" sx={{ mt: 2, height: 108, borderRadius: 1.5 }} />

    <Stack direction="row" spacing={1} sx={{ mt: 2, alignItems: 'center' }}>
      <Skeleton variant="rounded" width={92} height={32} />
      <Skeleton variant="rounded" width={104} height={32} />
    </Stack>

    <Skeleton variant="rounded" sx={{ mt: 1.5, height: 120, borderRadius: 1.5 }} />
  </>
);

export default PurgeDryRunCard;
