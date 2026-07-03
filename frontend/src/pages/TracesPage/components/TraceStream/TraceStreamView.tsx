import { useEffect, useMemo, useRef } from 'react';
import type { ReactNode } from 'react';
import { alpha, Box, Typography, useTheme } from '@mui/material';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutlined';
import AccountTreeIcon from '@mui/icons-material/AccountTree';
import { gradients } from '../../../../theme/colors';
import type { TraceRow } from '../../../../api';
import {
  durationMsOf,
  formatDuration,
  formatTokens,
  quantile,
  serviceOf,
  statusOf,
  tokensOf,
} from '../../tracesApi';
import TraceSummaryInline from '../TraceSummaryInline';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';

const GRID_TEMPLATE_COLUMNS =
  '4px 112px minmax(170px,1.35fr) 188px 78px 56px 74px 150px 24px';

const clockTime = (iso: string) =>
  new Date(iso).toLocaleTimeString('en-US', { hour12: false });

const HeaderCell = ({
  children,
  align,
}: {
  children?: ReactNode;
  align?: 'right';
}) => (
  <Typography
    component="span"
    sx={{
      typography: 'eyebrowSm',
      color: 'text.secondary',
      py: 1.25,
      textAlign: align,
      whiteSpace: 'nowrap',
    }}
  >
    {children}
  </Typography>
);

export interface TraceStreamViewProps {
  rows: TraceRow[];
  total: number;
  loading: boolean;
  hasMore: boolean;
  expanded: Set<string>;
  onToggleExpand: (traceId: string) => void;
  onLoadMore: () => void;
}

const TraceStreamView = ({
  rows,
  total,
  loading,
  hasMore,
  expanded,
  onToggleExpand,
  onLoadMore,
}: TraceStreamViewProps) => {
  const theme = useTheme();
  const scrollRef = useRef<HTMLDivElement>(null);

  // log-scaled latency domain across the loaded set + p95 marker
  const { latencyFillPercent, p95 } = useMemo(() => {
    const durations = rows.map(durationMsOf).sort((a, b) => a - b);
    const minDuration = Math.max(1, durations[0] ?? 1);
    const maxDuration = Math.max(
      minDuration + 1,
      durations[durations.length - 1] ?? 2,
    );
    const logMinDuration = Math.log(minDuration);
    const logMaxDuration = Math.log(maxDuration);
    return {
      p95: quantile(durations, 0.95),
      latencyFillPercent: (durationMs: number) =>
        ((Math.log(Math.max(1, durationMs)) - logMinDuration) /
          (logMaxDuration - logMinDuration)) *
        100,
    };
  }, [rows]);

  // load-on-scroll
  useEffect(() => {
    const scrollElement = scrollRef.current;
    if (!scrollElement) {
      return undefined;
    }
    const onScroll = () => {
      if (
        scrollElement.scrollTop + scrollElement.clientHeight >=
        scrollElement.scrollHeight - 240
      ) {
        onLoadMore();
      }
    };
    scrollElement.addEventListener('scroll', onScroll);
    return () => scrollElement.removeEventListener('scroll', onScroll);
  }, [onLoadMore]);

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
        overflow: 'hidden',
        flex: 1,
      }}
    >
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: GRID_TEMPLATE_COLUMNS,
          alignItems: 'center',
          gap: 1.5,
          pr: 2,
          borderBottom: 1,
          borderColor: 'divider',
          bgcolor: 'background.paper',
        }}
      >
        <span />
        <HeaderCell>Start</HeaderCell>
        <HeaderCell>Operation</HeaderCell>
        <HeaderCell>Latency</HeaderCell>
        <HeaderCell align="right">Duration</HeaderCell>
        <HeaderCell align="right">Spans</HeaderCell>
        <HeaderCell align="right">Tokens</HeaderCell>
        <HeaderCell>Session</HeaderCell>
        <span />
      </Box>

      <Box ref={scrollRef} sx={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        {rows.length === 0 && !loading ? (
          <Box
            sx={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              height: '100%',
              gap: 1.5,
              color: 'text.secondary',
              textAlign: 'center',
              p: 5,
            }}
          >
            <AccountTreeIcon sx={{ fontSize: 40, color: 'text.disabled' }} />
            <Typography
              sx={{
                fontFamily: fontFamilies.display,
                color: 'text.primary',
                fontSize: 16,
                fontWeight: 700,
              }}
            >
              No traces match
            </Typography>
            <Typography sx={{ fontSize: 13 }}>
              Try widening the time window or clearing facets.
            </Typography>
          </Box>
        ) : null}

        {rows.map((trace) => {
          const status = statusOf(trace);
          const durationMs = durationMsOf(trace);
          const isExpanded = expanded.has(trace.traceId);
          const fillPercent = Math.max(3, latencyFillPercent(durationMs));
          const isSlow = durationMs >= p95;
          const serviceName = serviceOf(trace.rootSpanName);
          return (
            <Box key={trace.traceId}>
              <Box
                onClick={() => onToggleExpand(trace.traceId)}
                sx={{
                  display: 'grid',
                  gridTemplateColumns: GRID_TEMPLATE_COLUMNS,
                  alignItems: 'center',
                  gap: 1.5,
                  pr: 2,
                  borderBottom: 1,
                  borderColor: 'divider',
                  cursor: 'pointer',
                  minHeight: 42,
                  bgcolor: isExpanded ? 'action.hover' : 'transparent',
                  '&:hover': { bgcolor: 'action.hover' },
                }}
              >
                <Box
                  sx={{
                    alignSelf: 'stretch',
                    bgcolor:
                      status === 'error'
                        ? 'error.main'
                        : isExpanded
                          ? 'primary.main'
                          : 'transparent',
                  }}
                />
                <Box
                  sx={{
                    typography: 'mono',
                    fontSize: 12,
                    color: 'text.secondary',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {clockTime(trace.startTimestamp)}
                </Box>
                <Box
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1,
                    minWidth: 0,
                  }}
                >
                  <Box
                    component="span"
                    sx={{
                      typography: 'mono',
                      fontSize: 13,
                      color: 'text.primary',
                      fontWeight: 500,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                    }}
                  >
                    {trace.rootSpanName}
                  </Box>
                  <Box
                    component="span"
                    sx={{
                      typography: 'mono',
                      display: 'inline-flex',
                      alignItems: 'center',
                      height: 18,
                      px: 0.9,
                      borderRadius: radii.xs,
                      bgcolor: 'action.hover',
                      color: 'text.secondary',
                      fontSize: 10.5,
                      fontWeight: 500,
                      whiteSpace: 'nowrap',
                      flexShrink: 0,
                    }}
                  >
                    {trace.rootSpanName?.startsWith('mcp.')
                      ? 'mcp'
                      : serviceName.replace('claude_code.', '')}
                  </Box>
                  {trace.errorCount ? (
                    <Box
                      component="span"
                      sx={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 0.5,
                        height: 19,
                        px: 1,
                        borderRadius: radii.xs,
                        color: 'error.main',
                        bgcolor: (th) => alpha(th.palette.error.main, 0.14),
                        fontFamily: fontFamilies.display,
                        fontSize: 10,
                        fontWeight: 700,
                        flexShrink: 0,
                      }}
                    >
                      <ErrorOutlineIcon sx={{ fontSize: 11 }} />
                      {trace.errorCount}
                    </Box>
                  ) : null}
                </Box>
                <Box
                  sx={{ display: 'flex', alignItems: 'center', height: '100%' }}
                >
                  <Box
                    sx={{
                      position: 'relative',
                      width: '100%',
                      height: 16,
                      borderRadius: radii.xs,
                      bgcolor: (th) => alpha(th.palette.primary.main, 0.11),
                      overflow: 'hidden',
                    }}
                  >
                    <Box
                      sx={{
                        position: 'absolute',
                        left: 0,
                        top: 0,
                        bottom: 0,
                        borderRadius: radii.xs,
                        width: `${fillPercent}%`,
                        minWidth: 3,
                        opacity: 0.85,
                        background:
                          status === 'error'
                            ? gradients.error
                            : `linear-gradient(90deg, ${theme.palette.primary.main}, ${theme.palette.primary.light})`,
                      }}
                    />
                    <Box
                      sx={{
                        position: 'absolute',
                        top: -2,
                        bottom: -2,
                        width: 2,
                        bgcolor: 'warning.main',
                        opacity: 0.7,
                        left: `${Math.min(99, latencyFillPercent(p95))}%`,
                      }}
                    />
                  </Box>
                </Box>
                <Box
                  sx={{
                    typography: 'mono',
                    fontSize: 12.5,
                    textAlign: 'right',
                    fontWeight: 500,
                    color: isSlow ? 'warning.main' : 'text.primary',
                  }}
                >
                  {formatDuration(trace.durationNanos)}
                </Box>
                <Box
                  sx={{
                    typography: 'mono',
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'flex-end',
                    gap: 0.6,
                    fontSize: 12,
                    color: 'text.secondary',
                  }}
                >
                  <AccountTreeIcon
                    sx={{ fontSize: 13, color: 'text.disabled' }}
                  />
                  {trace.spanCount}
                </Box>
                <Box
                  sx={{
                    typography: 'mono',
                    fontSize: 12,
                    textAlign: 'right',
                    color:
                      tokensOf(trace) > 0 ? 'text.secondary' : 'text.disabled',
                  }}
                >
                  {tokensOf(trace) > 0 ? formatTokens(tokensOf(trace)) : '—'}
                </Box>
                <Box
                  sx={{
                    typography: 'mono',
                    fontSize: 11.5,
                    color: 'text.disabled',
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                  }}
                >
                  {trace.sessionId ?? '—'}
                </Box>
                <Box
                  sx={{
                    display: 'grid',
                    placeItems: 'center',
                    color: isExpanded ? 'primary.main' : 'text.disabled',
                    transform: isExpanded ? 'rotate(90deg)' : 'none',
                    transition: 'transform .15s',
                  }}
                >
                  <ChevronRightIcon sx={{ fontSize: 16 }} />
                </Box>
              </Box>
              {isExpanded ? <TraceSummaryInline trace={trace} /> : null}
            </Box>
          );
        })}

        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 1.1,
            p: 1.6,
            fontSize: 12.5,
            color: 'text.secondary',
          }}
        >
          {loading ? (
            <>
              <Box
                sx={{
                  width: 14,
                  height: 14,
                  borderRadius: '50%',
                  border: 2,
                  borderColor: 'divider',
                  borderTopColor: 'primary.main',
                  animation: 'spin .7s linear infinite',
                  '@keyframes spin': { to: { transform: 'rotate(360deg)' } },
                }}
              />
              Loading…
            </>
          ) : rows.length === 0 ? null : hasMore ? (
            <span>
              Showing{' '}
              <b style={{ color: theme.palette.text.primary }}>{rows.length}</b>{' '}
              of{' '}
              <b style={{ color: theme.palette.text.primary }}>
                {total.toLocaleString()}
              </b>{' '}
              — scroll to load more
            </span>
          ) : (
            <Box component="span" sx={{ color: 'text.disabled' }}>
              — end of results · {total.toLocaleString()} traces —
            </Box>
          )}
        </Box>
      </Box>
    </Box>
  );
};

export default TraceStreamView;
