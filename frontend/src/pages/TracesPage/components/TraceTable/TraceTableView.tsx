import { Fragment } from 'react';
import type { ReactNode } from 'react';
import { alpha, Box, Typography } from '@mui/material';
import { auroraColors } from '../../../../theme/colors';
import type { TraceRow } from '../../../../api';
import {
  formatDuration,
  formatTokens,
  serviceOf,
  tokensOf,
} from '../../tracesApi';
import { serviceColor } from '../traceColors';
import TraceSummaryInline from '../TraceSummaryInline';
import TablePager from '../../../../components/TablePager';
import { fontFamilies } from '../../../../theme/typography';

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
      fontFamily: fontFamilies.display,
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
                                  ? alpha(auroraColors.violetLight, 0.04)
                                  : alpha(auroraColors.violet, 0.022)
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
                        fontFamily: fontFamilies.mono,
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
                        fontFamily: fontFamilies.mono,
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
                        fontFamily: fontFamilies.mono,
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
                        fontFamily: fontFamilies.mono,
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
                        fontFamily: fontFamilies.mono,
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
                        fontFamily: fontFamilies.mono,
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
                        fontFamily: fontFamilies.mono,
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
                        fontFamily: fontFamilies.mono,
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
                        fontFamily: fontFamilies.mono,
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
                fontFamily: fontFamilies.display,
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

      <TablePager
        page={page}
        pageSize={pageSize}
        rowCount={total}
        onPageChange={onPageChange}
        onPageSizeChange={onPageSizeChange}
      />
    </Box>
  );
};

export default TraceTableView;
