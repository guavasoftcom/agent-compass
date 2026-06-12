import { useState } from 'react';
import { Box, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, alpha } from '@mui/material';
import type { SxProps, Theme } from '@mui/material/styles';
import type { LogRow } from '../../../../api';
import { severityOf } from '../../logsApi';
import { SeverityChip } from '../LogStream';
import { AttributeList } from '../../../../components/AttributeList';
import { AttributeValue, type ValueDialogState } from '../../../../components/AttributeList/AttributeValue';
import { ExpandedValueDialog } from '../../../../components/AttributeList/ExpandedValueDialog';
import { PAGE_SIZES } from '../../../../constants';

// Targets MUI's cell classes (`.MuiTableCell-head` / `.MuiTableCell-body`) so the
// bespoke look wins over MUI's TableCell defaults; the sticky header is the `stickyHeader`
// prop on <Table>. Column tweaks key off the per-cell classNames (id/ts/body/scope/attr).
const tableSx: SxProps<Theme> = {
  minWidth: 1000,
  '& .MuiTableCell-head': {
    backgroundColor: 'background.paper',
    fontFamily: "'Sora', sans-serif",
    fontSize: '11px',
    fontWeight: 700,
    letterSpacing: '0.6px',
    textTransform: 'uppercase',
    color: 'text.secondary',
    textAlign: 'left',
    whiteSpace: 'nowrap',
    padding: '15px 14px 13px',
    borderBottom: 1,
    borderColor: 'divider',
  },
  '& .MuiTableCell-body': {
    fontFamily: "'Space Grotesk', sans-serif",
    padding: '11px 14px',
    fontSize: '13px',
    borderBottom: 1,
    borderColor: 'divider',
    color: 'text.primary',
    verticalAlign: 'top',
  },
  '& .MuiTableBody-root .MuiTableRow-root:nth-of-type(even) .MuiTableCell-body': {
    backgroundColor: (t) => alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.04 : 0.022),
  },
  '& .MuiTableBody-root .MuiTableRow-root:hover .MuiTableCell-body': { backgroundColor: 'action.hover' },
  '& .MuiTableCell-body.id': { color: 'text.disabled', fontVariantNumeric: 'tabular-nums' },
  '& .MuiTableCell-body.ts': { color: 'text.secondary', whiteSpace: 'nowrap', fontSize: '12.5px' },
  '& .MuiTableCell-body.body': { fontFamily: 'monospace', fontSize: '12px', color: 'text.primary', maxWidth: 380 },
  '& .MuiTableCell-body.scope': { fontFamily: 'monospace', fontSize: '12px', color: 'text.secondary', whiteSpace: 'nowrap' },
  '& .MuiTableCell-body.attr': { maxWidth: 320 },
  '& .MuiTableCell-body.state': { textAlign: 'center', color: 'text.secondary', padding: '40px 14px' },
};

const pagerNavSx: SxProps<Theme> = {
  width: 32,
  height: 32,
  display: 'grid',
  placeItems: 'center',
  borderRadius: 1.25,
  border: 1,
  borderColor: 'divider',
  bgcolor: 'background.paper',
  color: 'text.secondary',
  cursor: 'pointer',
  '&:hover:not(:disabled)': { color: 'primary.main', borderColor: 'primary.main' },
  '&:disabled': { opacity: 0.4, cursor: 'default' },
  '& svg': { width: 15, height: 15 },
};

interface Props {
  rows: LogRow[];
  total: number;
  page: number;
  pageSize: number;
  loading: boolean;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
}

const LogTableView = ({ rows, total, page, pageSize, loading, onPageChange, onPageSizeChange }: Props) => {
  const startIndex = page * pageSize;
  const maxPage = Math.max(0, Math.ceil(total / pageSize) - 1);
  const rangeLabel = total === 0 ? '0 of 0' : `${startIndex + 1}–${Math.min(startIndex + pageSize, total)} of ${total.toLocaleString()}`;
  // shared with the Stream: long values truncate to "View more" → repair modal
  const [dialog, setDialog] = useState<ValueDialogState | null>(null);
  // per-row "+N more" expansion (reveals attributes beyond the first 4)
  const [expandedRows, setExpandedRows] = useState<Set<number>>(() => new Set());
  const toggleRow = (id: number) =>
    setExpandedRows((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });

  return (
    <>
      <TableContainer sx={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        <Table stickyHeader sx={tableSx}>
          <TableHead>
            <TableRow>
              {['ID', 'Timestamp', 'Severity', 'Body', 'Scope', 'Attributes'].map((h) => (
                <TableCell key={h}>{h}</TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {loading && rows.length === 0 ? (
              <TableRow>
                <TableCell className="state" colSpan={6}>
                  Loading logs…
                </TableCell>
              </TableRow>
            ) : null}
            {!loading && rows.length === 0 ? (
              <TableRow>
                <TableCell className="state" colSpan={6}>
                  No logs in this window.
                </TableCell>
              </TableRow>
            ) : null}
            {rows.map((row) => {
              const attrs = row.attributes ?? {};
              const keys = Object.keys(attrs);
              const rowOpen = expandedRows.has(row.id);
              const shown = rowOpen ? keys : keys.slice(0, 4);
              return (
                <TableRow key={row.id}>
                  <TableCell className="id">{row.id}</TableCell>
                  <TableCell className="ts">
                    {new Date(row.timestamp).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                  </TableCell>
                  <TableCell>
                    <SeverityChip severity={severityOf(row)} />
                  </TableCell>
                  <TableCell className="body">{row.body}</TableCell>
                  <TableCell className="scope">{row.scopeName}</TableCell>
                  <TableCell className="attr">
                    {shown.map((k) => (
                      <Box
                        key={k}
                        component="span"
                        sx={{ display: 'inline-flex', flexWrap: 'wrap', alignItems: 'baseline', columnGap: 0.4, mr: 0.75, mb: 0.6, fontFamily: 'monospace', fontSize: 11, bgcolor: 'action.hover', borderRadius: 0.75, px: 0.9, py: 0.25, color: 'text.secondary', maxWidth: '100%', overflowWrap: 'anywhere' }}
                      >
                        {`${k}=`}
                        <Box component="span" sx={{ color: 'text.primary', fontWeight: 600 }}>
                          <AttributeValue attrKey={k} value={attrs[k]} truncate onExpand={setDialog} />
                        </Box>
                      </Box>
                    ))}
                    {keys.length > 4 ? (
                      <Box
                        component="button"
                        type="button"
                        onClick={() => toggleRow(row.id)}
                        sx={{ border: 'none', background: 'none', p: 0, cursor: 'pointer', fontFamily: "'Space Grotesk', sans-serif", color: 'primary.main', fontWeight: 600, fontSize: 12, '&:hover': { textDecoration: 'underline' } }}
                      >
                        {rowOpen ? 'show less' : `+${keys.length - 4} more`}
                      </Box>
                    ) : null}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>

      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'flex-end',
          flexWrap: 'wrap',
          gap: 2.25,
          px: 2,
          py: 1.5,
          flexShrink: 0,
          borderTop: 1,
          borderColor: 'divider',
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, fontSize: 12.5, color: 'text.secondary' }}>
          Rows per page
          <Box sx={{ display: 'inline-flex', bgcolor: 'action.hover', borderRadius: 1.25, p: '3px', gap: '2px' }}>
            {PAGE_SIZES.map((size) => {
              const on = size === pageSize;
              return (
                <Box
                  key={size}
                  component="button"
                  onClick={() => onPageSizeChange(size)}
                  sx={{
                    border: 'none',
                    cursor: 'pointer',
                    fontFamily: "'Sora', sans-serif",
                    fontSize: 12.5,
                    fontWeight: 600,
                    px: 1.4,
                    py: 0.5,
                    borderRadius: 1,
                    color: on ? 'primary.main' : 'text.secondary',
                    bgcolor: on ? 'background.paper' : 'transparent',
                    boxShadow: on ? 1 : 'none',
                    '&:hover': { color: 'text.primary' },
                  }}
                >
                  {size}
                </Box>
              );
            })}
          </Box>
        </Box>
        <Box sx={{ fontSize: 12.5, color: 'text.primary', fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{rangeLabel}</Box>
        <Box sx={{ display: 'flex', gap: 0.75 }}>
          <Box component="button" disabled={page <= 0} onClick={() => onPageChange(page - 1)} sx={pagerNavSx}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
              <path d="M15 6l-6 6 6 6" />
            </svg>
          </Box>
          <Box component="button" disabled={page >= maxPage} onClick={() => onPageChange(page + 1)} sx={pagerNavSx}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
              <path d="M9 6l6 6-6 6" />
            </svg>
          </Box>
        </Box>
      </Box>
      <ExpandedValueDialog
        state={dialog}
        onClose={() => setDialog(null)}
        renderAttributeList={(a) => <AttributeList attributes={a} inlineExpand />}
      />
    </>
  );
};

export default LogTableView;
