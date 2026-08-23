import {
  alpha,
  Box,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
  useTheme,
} from '@mui/material';
import { fontFamilies } from '../../../theme/typography';
import {
  formatCompact,
  formatRelativeTime,
  formatTimestamp,
} from '../../../lib/format';
import { ingestFreshness, type IngestFreshness } from '../settingsDerivations';
import type { IngestHealth } from '../settingsTypes';

export interface IngestHealthCardProps {
  ingestHealth: IngestHealth | null;
  isIngestLoading: boolean;
}

const numberCell = {
  textAlign: 'right' as const,
  fontVariantNumeric: 'tabular-nums',
};

const FRESHNESS_LABEL: Record<IngestFreshness, string> = {
  live: 'Live',
  delayed: 'Delayed',
  stale: 'Stale',
  empty: 'No data',
};

const FRESHNESS_EXPLANATION: Record<IngestFreshness, string> = {
  live: 'Received within the last 15 minutes.',
  delayed: 'Nothing received for over 15 minutes — the agent may be idle.',
  stale:
    'Nothing received for over 24 hours. The exporter or collector is probably not running.',
  empty: 'This signal has never been received.',
};

/**
 * Per-signal arrival status.
 *
 * The freshness chip reads `newestReceivedAt` — when this server persisted the
 * row — rather than the event time the agent stamped on it. That is the whole
 * point: every other page in the dashboard keeps rendering the last data it has
 * when ingest stops, without saying so.
 */
const IngestHealthCard = ({
  ingestHealth,
  isIngestLoading,
}: IngestHealthCardProps) => {
  const theme = useTheme();

  const freshnessColor = (freshness: IngestFreshness): string => {
    if (freshness === 'live') {
      return theme.palette.success.main;
    }
    if (freshness === 'delayed') {
      return theme.palette.warning.main;
    }
    if (freshness === 'stale') {
      return theme.palette.error.main;
    }
    return theme.palette.text.disabled;
  };

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px', mt: 2 }}>
      <Typography
        sx={{ fontFamily: fontFamilies.display, fontWeight: 600, fontSize: 16 }}
      >
        Ingest health
      </Typography>
      <Typography
        variant="body2"
        color="text.secondary"
        sx={{ mt: 0.75, mb: 1.5, lineHeight: 1.5 }}
      >
        Whether telemetry is still arriving, per OTLP signal. Status reflects
        when this server last received a row, not when the agent says the event
        happened.
      </Typography>

      {!ingestHealth && isIngestLoading ? (
        <Typography color="text.secondary">Measuring…</Typography>
      ) : !ingestHealth ? (
        <Typography color="text.secondary">No ingest data reported.</Typography>
      ) : (
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Signal</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Last received</TableCell>
                <TableCell sx={numberCell}>Last hour</TableCell>
                <TableCell sx={numberCell}>Last 24h</TableCell>
                <TableCell sx={numberCell}>Last 7d</TableCell>
                <TableCell sx={numberCell}>Distinct names</TableCell>
                <TableCell sx={numberCell}>Streams</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {ingestHealth.signals.map((signal) => {
                const freshness = ingestFreshness(signal);
                const color = freshnessColor(freshness);
                return (
                  <TableRow key={signal.signal} hover>
                    <TableCell
                      sx={{ fontWeight: 600, textTransform: 'capitalize' }}
                    >
                      {signal.signal}
                      <Box
                        component="span"
                        sx={{
                          typography: 'mono',
                          color: 'text.disabled',
                          ml: 1,
                          fontSize: 12,
                        }}
                      >
                        {signal.tableName}
                      </Box>
                    </TableCell>
                    <TableCell>
                      <Tooltip arrow title={FRESHNESS_EXPLANATION[freshness]}>
                        <Box
                          component="span"
                          sx={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 0.7,
                            px: 1,
                            height: 24,
                            borderRadius: '7px',
                            fontSize: 12,
                            fontWeight: 700,
                            cursor: 'help',
                            color,
                            bgcolor: alpha(color, 0.16),
                          }}
                        >
                          <Box
                            component="span"
                            sx={{
                              width: 7,
                              height: 7,
                              borderRadius: '50%',
                              bgcolor: color,
                            }}
                          />
                          {FRESHNESS_LABEL[freshness]}
                        </Box>
                      </Tooltip>
                    </TableCell>
                    <TableCell sx={{ color: 'text.secondary' }}>
                      {signal.newestReceivedAt ? (
                        <Tooltip
                          arrow
                          title={formatTimestamp(signal.newestReceivedAt)}
                        >
                          <Box component="span" sx={{ cursor: 'help' }}>
                            {formatRelativeTime(signal.newestReceivedAt)}
                          </Box>
                        </Tooltip>
                      ) : (
                        '—'
                      )}
                    </TableCell>
                    <TableCell sx={numberCell}>
                      {formatCompact(signal.rowsLastHour)}
                    </TableCell>
                    <TableCell sx={numberCell}>
                      {formatCompact(signal.rowsLastDay)}
                    </TableCell>
                    <TableCell sx={{ ...numberCell, fontWeight: 700 }}>
                      {formatCompact(signal.rowsLastWeek)}
                    </TableCell>
                    <TableCell sx={numberCell}>
                      <Tooltip
                        arrow
                        title={`Distinct ${signal.nameCardinalityLabel} values`}
                      >
                        <Box component="span" sx={{ cursor: 'help' }}>
                          {formatCompact(signal.nameCardinality)}
                        </Box>
                      </Tooltip>
                    </TableCell>
                    <TableCell sx={numberCell}>
                      {signal.seriesCardinality === null ? (
                        <Box component="span" sx={{ color: 'text.disabled' }}>
                          —
                        </Box>
                      ) : (
                        <Tooltip
                          arrow
                          title="Distinct metric streams — one per metric name and full attribute set"
                        >
                          <Box component="span" sx={{ cursor: 'help' }}>
                            {formatCompact(signal.seriesCardinality)}
                          </Box>
                        </Tooltip>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Box>
      )}
    </Paper>
  );
};

export default IngestHealthCard;
