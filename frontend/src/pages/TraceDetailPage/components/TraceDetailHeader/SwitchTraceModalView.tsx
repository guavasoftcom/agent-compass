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
import { Box, Dialog, DialogContent, Typography } from '@mui/material';
import type { SessionPromptRow } from '../../../../api';
import GhostButton from '../../../../components/GhostButton';
import { radii } from '../../../../theme/theme';
import { LongValueModalProvider } from '../SpanInspectorDrawer/longValue';
import SwitchTraceModalRow from './SwitchTraceModalRow';

// A SessionPromptRow known to carry both a trace and a prompt — the two
// fields SwitchTraceModal filters the raw timeline down to before this view
// ever sees a row.
export type SwitchTraceRow = SessionPromptRow & {
  traceId: string;
  prompt: string;
};

// Rows without a trace (pre-tracing sessions) or a prompt (capture disabled)
// aren't traces a reader can jump to. Shared by SwitchTraceModal (the row
// list) and IdentityPill (which needs the same filter to count distinct
// traces before deciding whether switching is worth offering at all).
export const hasTraceAndPrompt = (
  row: SessionPromptRow,
): row is SwitchTraceRow => row.traceId !== null && row.prompt !== null;

interface Props {
  open: boolean;
  onClose: () => void;
  sessionId: string;
  currentTraceId: string;
  rows: SwitchTraceRow[];
  isLoading: boolean;
  onSelectTrace: (traceId: string) => void;
}

// One row per turn in the session, newest (current) at the bottom — the order
// `fetchSessionPrompts` already returns. Only the current row (matching the
// page's own traceId) is flagged; an ERROR flag would need a per-row
// cross-reference against each trace's error count, which the prompts
// endpoint doesn't carry and which fetching per-row here would turn into an
// N+1 on every open of this modal — left for a future backend field rather
// than N extra requests per click. Wrapped in LongValueModalProvider (the
// same "view formatted" dialog SpanInspectorDrawer uses) so a row's prompt —
// a long ordinary message, or a <task-notification> envelope's raw XML — can
// be opened full-size instead of only ever showing as a clipped one-liner;
// see SwitchTraceModalRow for what triggers it per row.
const SwitchTraceModalView = ({
  open,
  onClose,
  sessionId,
  currentTraceId,
  rows,
  isLoading,
  onSelectTrace,
}: Props) => (
  <Dialog
    open={open}
    onClose={onClose}
    maxWidth={false}
    slotProps={{
      paper: {
        sx: {
          width: 'min(800px, 92vw)',
          maxHeight: '64vh',
          borderRadius: radii.lg,
          display: 'flex',
          flexDirection: 'column',
        },
      },
    }}
  >
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 1.5,
        px: 2.5,
        py: 1.75,
        borderBottom: 1,
        borderColor: 'divider',
        flexShrink: 0,
      }}
    >
      <Typography
        sx={{ fontSize: 14.5, fontWeight: 700, color: 'text.primary' }}
      >
        Switch trace{' '}
        <Box
          component="span"
          sx={{
            typography: 'mono',
            fontSize: 12.5,
            fontWeight: 400,
            color: 'text.secondary',
          }}
        >
          · session {sessionId}
        </Box>
      </Typography>
      <GhostButton onClick={onClose}>Close</GhostButton>
    </Box>
    <DialogContent sx={{ p: 0, overflowY: 'auto' }}>
      {isLoading ? (
        <Box sx={{ p: 3, fontSize: 13, color: 'text.secondary' }}>Loading…</Box>
      ) : rows.length === 0 ? (
        <Box sx={{ p: 3, fontSize: 13, color: 'text.secondary' }}>
          No other traces in this session.
        </Box>
      ) : (
        <LongValueModalProvider>
          {rows.map((row) => (
            <SwitchTraceModalRow
              key={row.traceId}
              row={row}
              isCurrent={row.traceId === currentTraceId}
              onSelect={() => onSelectTrace(row.traceId)}
            />
          ))}
        </LongValueModalProvider>
      )}
    </DialogContent>
  </Dialog>
);

export default SwitchTraceModalView;
