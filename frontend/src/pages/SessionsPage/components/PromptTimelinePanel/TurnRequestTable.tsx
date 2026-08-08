import { Box, Tooltip } from '@mui/material';
import { alpha } from '@mui/material/styles';
import type { Theme } from '@mui/material/styles';
import type { SessionApiRequestRow } from '../../../../api';
import { fontFamilies } from '../../../../theme/typography';
import { formatTokens } from '../sessionsFormat';

const USD_FORMATTER = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 2,
  maximumFractionDigits: 4,
});

const TIME_FORMATTER = new Intl.DateTimeFormat('en-US', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

const MILLISECONDS_PER_SECOND = 1000;

const formatRequestDuration = (durationMs: number | null): string => {
  if (durationMs == null || !Number.isFinite(durationMs)) {
    return '—';
  }
  if (durationMs < MILLISECONDS_PER_SECOND) {
    return `${Math.round(durationMs)}ms`;
  }
  return `${(durationMs / MILLISECONDS_PER_SECOND).toFixed(1)}s`;
};

export interface TurnRequestTableProps {
  requests: SessionApiRequestRow[];
}

/**
 * The per-request drill-down inside one turn card: every LLM call the turn
 * issued, with the exact tokens, cost and duration reported for it.
 *
 * These rows are the turn's headline figures unrolled — the card's cost and
 * token totals are literally these rows summed — so nothing here needs its own
 * "approximate" caveat. Turns whose figures came from the counter fallback have
 * no requests to show and never render this table.
 */
const TurnRequestTable = ({ requests }: TurnRequestTableProps) => {
  if (requests.length === 0) {
    return null;
  }

  const cellSx = {
    px: 0.75,
    py: 0.5,
    fontSize: 11,
    whiteSpace: 'nowrap',
    fontVariantNumeric: 'tabular-nums',
  } as const;

  return (
    <Box
      sx={{
        mt: 0.5,
        border: 1,
        borderColor: 'divider',
        borderRadius: 1.2,
        overflowX: 'auto',
        bgcolor: (t: Theme) => alpha(t.palette.text.primary, t.palette.mode === 'dark' ? 0.02 : 0.015),
      }}
    >
      <Box component="table" sx={{ width: '100%', borderCollapse: 'collapse' }}>
        <Box
          component="thead"
          sx={{
            '& th': {
              ...cellSx,
              textAlign: 'left',
              fontFamily: fontFamilies.display,
              fontSize: 9.5,
              fontWeight: 700,
              letterSpacing: '0.8px',
              textTransform: 'uppercase',
              color: 'text.disabled',
              borderBottom: 1,
              borderColor: 'divider',
            },
          }}
        >
          <Box component="tr">
            <Box component="th">Time</Box>
            <Box component="th">Model</Box>
            <Box component="th">Effort</Box>
            <Box component="th" sx={{ textAlign: 'right' }}>Tokens</Box>
            <Box component="th" sx={{ textAlign: 'right' }}>Cache read</Box>
            <Box component="th" sx={{ textAlign: 'right' }}>Cost</Box>
            <Box component="th" sx={{ textAlign: 'right' }}>Duration</Box>
          </Box>
        </Box>
        <Box
          component="tbody"
          sx={{
            '& td': { ...cellSx, borderBottom: 1, borderColor: 'divider' },
            '& tr:last-of-type td': { borderBottom: 0 },
          }}
        >
          {requests.map((request, index) => {
            const totalTokens =
              request.tokens.input
              + request.tokens.output
              + request.tokens.cacheCreation
              + request.tokens.cacheRead;
            return (
              <Box
                component="tr"
                key={request.requestId ?? `${request.timestamp}-${index}`}
                sx={{
                  bgcolor: (t: Theme) =>
                    (index % 2 ? alpha(t.palette.text.primary, 0.02) : 'transparent'),
                }}
              >
                <Box component="td" sx={{ fontFamily: fontFamilies.mono, color: 'text.disabled' }}>
                  <Tooltip title={request.requestId ?? 'no request id'} placement="top" arrow>
                    <Box component="span" sx={{ cursor: 'help' }}>
                      {TIME_FORMATTER.format(new Date(request.timestamp))}
                    </Box>
                  </Tooltip>
                </Box>
                <Box component="td" sx={{ color: 'text.secondary' }}>{request.model ?? '—'}</Box>
                {/* effort is genuinely absent on a minority of rows; an em dash
                    says "not recorded" rather than implying a default was used */}
                <Box component="td" sx={{ color: 'text.secondary' }}>{request.effort ?? '—'}</Box>
                <Box component="td" sx={{ textAlign: 'right', fontWeight: 700 }}>
                  {formatTokens(totalTokens)}
                </Box>
                <Box component="td" sx={{ textAlign: 'right', color: 'text.secondary' }}>
                  {formatTokens(request.tokens.cacheRead)}
                </Box>
                <Box component="td" sx={{ textAlign: 'right', fontWeight: 700 }}>
                  {request.costUsd == null ? '—' : USD_FORMATTER.format(request.costUsd)}
                </Box>
                <Box component="td" sx={{ textAlign: 'right', color: 'text.secondary' }}>
                  {formatRequestDuration(request.durationMs)}
                </Box>
              </Box>
            );
          })}
        </Box>
      </Box>
    </Box>
  );
};

export default TurnRequestTable;
