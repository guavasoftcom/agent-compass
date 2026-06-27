import { Fragment } from 'react';
import type { ReactNode } from 'react';
import { Box, Typography, useTheme } from '@mui/material';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft';
import GhostButton from '../../../../components/GhostButton';
import type { TraceRow } from '../../../../api';
import {
  formatDuration,
  formatTokens,
  serviceOf,
  tokensOf,
} from '../../tracesApi';
import { serviceColor } from '../traceColors';
import TraceSummaryInline from '../TraceSummaryInline';

const PAGE_SIZES = [25, 50, 100];

const TableHeaderCell = ({
  children,
  align,
}: {
  children?: ReactNode;
  align?: 'right';
}) => (
  <Box
    component="th"
    sx={{
      position: 'sticky',
      top: 0,
      zIndex: 2,
      fontFamily: "'Sora', sans-serif",
      fontSize: 11,
      fontWeight: 700,
      letterSpacing: '0.6px',
      textTransform: 'uppercase',
      color: 'text.secondary',
      textAlign: align ?? 'left',
      px: 1.75,
      py: 1.5,
      borderBottom: 1,
      borderColor: 'divider',
      whiteSpace: 'nowrap',
      bgcolor: 'background.paper',
    }}
  >
    {children}
  </Box>
);

export interface TraceTableViewProps {
  rows: TraceRow[];
  total: number;
  page: number;
  pageSize: number;
  loading: boolean;
  expanded: Set<string>;
  onToggleExpand: (traceId: string) => void;
  onPageChange: (page: number) => void;
  onPageSizeChange: (pageSize: number) => void;
}

const TraceTableView = ({
  rows,
  total,
  page,
  pageSize,
  loading,
  expanded,
  onToggleExpand,
  onPageChange,
  onPageSizeChange,
}: TraceTableViewProps) => {
  const theme = useTheme();
  const start = page * pageSize;
  const end = Math.min(start + pageSize, total);
  const cellSx = {
    px: 1.75,
    py: 1.4,
    fontSize: 13,
    borderBottom: 1,
    borderColor: 'divider',
    verticalAlign: 'middle' as const,
  };

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
      <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        <Box
          component="table"
          sx={{ width: '100%', borderCollapse: 'collapse', minWidth: 980 }}
        >
          <Box component="thead">
            <Box component="tr">
              <TableHeaderCell>Start</TableHeaderCell>
              <TableHeaderCell>Root span</TableHeaderCell>
              <TableHeaderCell>Service</TableHeaderCell>
              <TableHeaderCell align="right">Duration</TableHeaderCell>
              <TableHeaderCell align="right">Spans</TableHeaderCell>
              <TableHeaderCell align="right">Tokens</TableHeaderCell>
              <TableHeaderCell align="right">Errors</TableHeaderCell>
              <TableHeaderCell>Session</TableHeaderCell>
              <TableHeaderCell>Trace ID</TableHeaderCell>
            </Box>
          </Box>
          <Box component="tbody">
            {rows.map((trace, i) => {
              const isExpanded = expanded.has(trace.traceId);
              const serviceName = serviceOf(trace.rootSpanName);
              return (
                <Fragment key={trace.traceId}>
                  <Box
                    component="tr"
                    onClick={() => onToggleExpand(trace.traceId)}
                    sx={{
                      cursor: 'pointer',
                      '& > td': {
                        bgcolor: isExpanded
                          ? 'action.hover'
                          : i % 2
                            ? (th) =>
                                th.palette.mode === 'dark'
                                  ? 'rgba(139,92,255,0.04)'
                                  : 'rgba(124,77,255,0.022)'
                            : 'transparent',
                      },
                      '&:hover > td': { bgcolor: 'action.hover' },
                    }}
                  >
                    <Box
                      component="td"
                      sx={{
                        ...cellSx,
                        color: 'text.secondary',
                        whiteSpace: 'nowrap',
                        fontFamily: "'JetBrains Mono', monospace",
                        fontSize: 12.5,
                      }}
                    >
                      <Box
                        component="span"
                        sx={{
                          display: 'inline-block',
                          width: 14,
                          color: 'text.disabled',
                          transform: isExpanded ? 'rotate(90deg)' : 'none',
                          transition: 'transform .15s',
                        }}
                      >
                        ›
                      </Box>
                      {new Date(trace.startTimestamp).toLocaleString()}
                    </Box>
                    <Box
                      component="td"
                      sx={{
                        ...cellSx,
                        fontFamily: "'JetBrains Mono', monospace",
                        fontSize: 12.5,
                        color: 'text.primary',
                      }}
                    >
                      {trace.rootSpanName}
                    </Box>
                    <Box
                      component="td"
                      sx={{
                        ...cellSx,
                        fontFamily: "'JetBrains Mono', monospace",
                        fontSize: 12.5,
                      }}
                    >
                      <Box
                        component="span"
                        sx={{
                          display: 'inline-flex',
                          alignItems: 'center',
                          gap: 0.75,
                        }}
                      >
                        <Box
                          sx={{
                            width: 8,
                            height: 8,
                            borderRadius: 0.5,
                            bgcolor: serviceColor(serviceName),
                          }}
                        />
                        {serviceName}
                      </Box>
                    </Box>
                    <Box
                      component="td"
                      sx={{
                        ...cellSx,
                        textAlign: 'right',
                        fontVariantNumeric: 'tabular-nums',
                        fontFamily: "'JetBrains Mono', monospace",
                      }}
                    >
                      {formatDuration(trace.durationNanos)}
                    </Box>
                    <Box
                      component="td"
                      sx={{
                        ...cellSx,
                        textAlign: 'right',
                        fontVariantNumeric: 'tabular-nums',
                        fontFamily: "'JetBrains Mono', monospace",
                      }}
                    >
                      {trace.spanCount}
                    </Box>
                    <Box
                      component="td"
                      sx={{
                        ...cellSx,
                        textAlign: 'right',
                        fontVariantNumeric: 'tabular-nums',
                        fontFamily: "'JetBrains Mono', monospace",
                        color:
                          tokensOf(trace) > 0
                            ? 'text.secondary'
                            : 'text.disabled',
                      }}
                    >
                      {tokensOf(trace) > 0
                        ? formatTokens(tokensOf(trace))
                        : '—'}
                    </Box>
                    <Box
                      component="td"
                      sx={{
                        ...cellSx,
                        textAlign: 'right',
                        fontVariantNumeric: 'tabular-nums',
                        fontFamily: "'JetBrains Mono', monospace",
                        color: trace.errorCount
                          ? 'error.main'
                          : 'text.disabled',
                        fontWeight: trace.errorCount ? 600 : 400,
                      }}
                    >
                      {trace.errorCount || '—'}
                    </Box>
                    <Box
                      component="td"
                      sx={{
                        ...cellSx,
                        fontFamily: "'JetBrains Mono', monospace",
                        fontSize: 11.5,
                        color: 'text.disabled',
                      }}
                    >
                      {trace.sessionId ?? '—'}
                    </Box>
                    <Box
                      component="td"
                      sx={{
                        ...cellSx,
                        fontFamily: "'JetBrains Mono', monospace",
                        fontSize: 11.5,
                        color: 'text.disabled',
                      }}
                    >{`${trace.traceId.slice(0, 14)}…`}</Box>
                  </Box>
                  {isExpanded ? (
                    <Box component="tr">
                      <Box
                        component="td"
                        colSpan={9}
                        sx={{ p: 0, borderBottom: 1, borderColor: 'divider' }}
                      >
                        <TraceSummaryInline trace={trace} />
                      </Box>
                    </Box>
                  ) : null}
                </Fragment>
              );
            })}
          </Box>
        </Box>
        {rows.length === 0 && !loading ? (
          <Box sx={{ p: 5, textAlign: 'center', color: 'text.secondary' }}>
            <Typography
              sx={{
                fontFamily: "'Sora', sans-serif",
                fontSize: 15,
                fontWeight: 700,
                color: 'text.primary',
              }}
            >
              No traces match
            </Typography>
          </Box>
        ) : null}
      </Box>

      {/* pager */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'flex-end',
          gap: 2.25,
          px: 2,
          py: 1.5,
          borderTop: 1,
          borderColor: 'divider',
          flexWrap: 'wrap',
          flexShrink: 0,
        }}
      >
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1.1,
            fontSize: 12.5,
            color: 'text.secondary',
          }}
        >
          Rows per page
          <Box
            sx={{
              display: 'inline-flex',
              bgcolor: 'action.hover',
              borderRadius: 1.1,
              p: '3px',
              gap: '2px',
            }}
          >
            {PAGE_SIZES.map((size) => {
              const isSelected = size === pageSize;
              return (
                <Box
                  key={size}
                  component="button"
                  onClick={() => onPageSizeChange(size)}
                  sx={{
                    border: 'none',
                    bgcolor: isSelected ? 'background.paper' : 'transparent',
                    color: isSelected ? 'primary.main' : 'text.secondary',
                    boxShadow: isSelected ? 1 : 'none',
                    fontFamily: "'Sora', sans-serif",
                    fontSize: 12.5,
                    fontWeight: 600,
                    px: 1.4,
                    py: 0.5,
                    borderRadius: 0.9,
                    cursor: 'pointer',
                  }}
                >
                  {size}
                </Box>
              );
            })}
          </Box>
        </Box>
        <Box
          sx={{
            fontSize: 12.5,
            color: 'text.secondary',
            fontVariantNumeric: 'tabular-nums',
          }}
        >
          {total ? (
            <>
              <b style={{ color: theme.palette.text.primary }}>
                {start + 1}–{end}
              </b>{' '}
              of{' '}
              <b style={{ color: theme.palette.text.primary }}>
                {total.toLocaleString()}
              </b>
            </>
          ) : (
            '0 of 0'
          )}
        </Box>
        <Box sx={{ display: 'flex', gap: 0.75 }}>
          {[
            {
              direction: -1,
              icon: <ChevronLeftIcon sx={{ fontSize: 15 }} />,
              disabled: page === 0,
            },
            {
              direction: 1,
              icon: <ChevronRightIcon sx={{ fontSize: 15 }} />,
              disabled: end >= total,
            },
          ].map((pagerButton, index) => (
            <GhostButton
              key={index}
              disabled={pagerButton.disabled}
              onClick={() => onPageChange(page + pagerButton.direction)}
              sx={{
                width: 32,
                height: 32,
                px: 0,
                display: 'grid',
                placeItems: 'center',
                '&:hover': pagerButton.disabled
                  ? {}
                  : { color: 'primary.main' },
              }}
            >
              {pagerButton.icon}
            </GhostButton>
          ))}
        </Box>
      </Box>
    </Box>
  );
};

export default TraceTableView;
