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
import { Box, Dialog, DialogContent, Typography } from '@mui/material';
import GhostButton from '../../../../components/GhostButton';
import { formatUsd } from '../../../TracesPage/tracesApi';
import { formatTokens } from '../../../SessionsPage/components/sessionsFormat';
import { radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import { LongValueModalProvider } from '../SpanInspectorDrawer/longValue';
import SwitchTraceModalRow from './SwitchTraceModalRow';
import type { NestedSwitchTraceRow } from './switchTraceRows';
import type { SessionPromptRow } from '../../../../api';

// Re-exported so `IdentityPill.tsx` and existing test imports keep working
// unchanged — the definitions themselves now live in `switchTraceRows.ts`
// alongside the nesting algorithm that also depends on them.
export { hasTraceAndPrompt, type SwitchTraceRow } from './switchTraceRows';

interface Props {
  open: boolean;
  onClose: () => void;
  sessionId: string;
  currentTraceId: string;
  rows: NestedSwitchTraceRow[];
  isLoading: boolean;
  onSelectTrace: (traceId: string) => void;
  prompts: SessionPromptRow[] | undefined;
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
  prompts,
}: Props) => {
  const sessionTotals = useMemo(() => {
    if (!prompts) {
      return { totalCostUsd: 0, totalTokens: 0 };
    }
    let totalCostUsd = 0;
    let totalTokens = 0;
    prompts.forEach((row) => {
      totalCostUsd += row.costUsd ?? 0;
      if (row.tokens) {
        totalTokens += row.tokens.input + row.tokens.output + row.tokens.cacheCreation + row.tokens.cacheRead;
      }
    });
    return { totalCostUsd, totalTokens };
  }, [prompts]);

  return (
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
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 2,
        px: 2.5,
        py: 1.5,
        borderBottom: 1,
        borderTop: 1,
        borderColor: 'divider',
        bgcolor: 'action.hover',
        flexShrink: 0,
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'stretch', gap: 3 }}>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.4 }}>
          <Box
            sx={{
              fontFamily: fontFamilies.body,
              fontSize: 9.5,
              fontWeight: 700,
              letterSpacing: '0.5px',
              textTransform: 'uppercase',
              color: 'text.disabled',
            }}
          >
            Session cost
          </Box>
          <Box
            sx={{
              typography: 'mono',
              fontSize: 14,
              fontWeight: 600,
              color: 'warning.main',
            }}
          >
            {formatUsd(sessionTotals.totalCostUsd)}
          </Box>
        </Box>
        <Box sx={{ width: '1px', flexShrink: 0, bgcolor: 'divider' }} />
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.4 }}>
          <Box
            sx={{
              fontFamily: fontFamilies.body,
              fontSize: 9.5,
              fontWeight: 700,
              letterSpacing: '0.5px',
              textTransform: 'uppercase',
              color: 'text.disabled',
            }}
          >
            Session tokens
          </Box>
          <Box sx={{ display: 'flex', gap: 0.3, alignItems: 'baseline' }}>
            <Box
              sx={{
                typography: 'mono',
                fontSize: 14,
                fontWeight: 600,
                color: 'text.primary',
              }}
            >
              {sessionTotals.totalTokens > 0 ? formatTokens(sessionTotals.totalTokens) : '—'}
            </Box>
            {sessionTotals.totalTokens > 0 && (
              <Box
                sx={{
                  typography: 'mono',
                  fontSize: 12,
                  color: 'text.secondary',
                }}
              >
                tok
              </Box>
            )}
          </Box>
        </Box>
      </Box>
      <Box
        sx={{
          typography: 'mono',
          fontSize: 12,
          color: 'text.disabled',
          whiteSpace: 'nowrap',
        }}
      >
        {rows.length} {rows.length === 1 ? 'trace' : 'traces'}
      </Box>
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
          {rows.map(({ row, depth, railBelow }) => (
            <SwitchTraceModalRow
              key={row.traceId}
              row={row}
              isCurrent={row.traceId === currentTraceId}
              onSelect={() => onSelectTrace(row.traceId)}
              depth={depth}
              railBelow={railBelow}
            />
          ))}
        </LongValueModalProvider>
      )}
    </DialogContent>
  </Dialog>
  );
};

export default SwitchTraceModalView;
