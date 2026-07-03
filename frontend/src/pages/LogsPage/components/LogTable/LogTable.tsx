import { useState } from 'react';
import { Box, alpha } from '@mui/material';
import type { SxProps, Theme } from '@mui/material/styles';
import type { LogRow } from '../../../../api';
import { AttributeList } from '../../../../components/AttributeList';
import { type ValueDialogState } from '../../../../components/AttributeList/AttributeValue';
import { ExpandedValueDialog } from '../../../../components/AttributeList/ExpandedValueDialog';
import { fontFamilies } from '../../../../theme/typography';
import TablePager from '../../../../components/TablePager';
import LogTableRow from './LogTableRow';

const tableSx: SxProps<Theme> = {
  width: '100%',
  borderCollapse: 'collapse',
  minWidth: 1000,
  fontFamily: fontFamilies.body,
  '& thead th': {
    position: 'sticky',
    top: 0,
    zIndex: 1,
    backgroundColor: 'background.paper',
    typography: 'eyebrowSm',
    color: 'text.secondary',
    textAlign: 'left',
    whiteSpace: 'nowrap',
    padding: '15px 14px 13px',
    borderBottom: 1,
    borderColor: 'divider',
  },
  '& tbody td': {
    padding: '11px 14px',
    fontSize: '13px',
    borderBottom: 1,
    borderColor: 'divider',
    color: 'text.primary',
    verticalAlign: 'top',
  },
  '& tbody tr:nth-of-type(even) td': {
    backgroundColor: (t) =>
      alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.04 : 0.022),
  },
  '& tbody tr:hover td': { backgroundColor: 'action.hover' },
  '& td.id': { color: 'text.disabled', fontVariantNumeric: 'tabular-nums' },
  '& td.ts': {
    color: 'text.secondary',
    whiteSpace: 'nowrap',
    fontSize: '12.5px',
  },
  '& td.body': {
    typography: 'mono',
    fontSize: '12px',
    color: 'text.primary',
    maxWidth: 380,
  },
  '& td.scope': {
    typography: 'mono',
    fontSize: '12px',
    color: 'text.secondary',
    whiteSpace: 'nowrap',
  },
  '& td.attr': { maxWidth: 320 },
  '& td.state': {
    textAlign: 'center',
    color: 'text.secondary',
    padding: '40px 14px',
  },
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

const LogTable = ({
  rows,
  total,
  page,
  pageSize,
  loading,
  onPageChange,
  onPageSizeChange,
}: Props) => {
  // shared with the Stream: long values truncate to "View more" → repair modal
  const [dialog, setDialog] = useState<ValueDialogState | null>(null);
  // per-row "+N more" expansion (reveals attributes beyond the first 4)
  const [expandedRows, setExpandedRows] = useState<Set<number>>(new Set());
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
      <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        <Box component="table" sx={tableSx}>
          <Box component="thead">
            <Box component="tr">
              {[
                'ID',
                'Timestamp',
                'Severity',
                'Body',
                'Scope',
                'Attributes',
              ].map((h) => (
                <Box component="th" key={h}>
                  {h}
                </Box>
              ))}
            </Box>
          </Box>
          <Box component="tbody">
            {loading && rows.length === 0 ? (
              <Box component="tr">
                <Box component="td" className="state" colSpan={6}>
                  Loading logs…
                </Box>
              </Box>
            ) : null}
            {!loading && rows.length === 0 ? (
              <Box component="tr">
                <Box component="td" className="state" colSpan={6}>
                  No logs in this window.
                </Box>
              </Box>
            ) : null}
            {rows.map((row) => (
              <LogTableRow
                key={row.id}
                row={row}
                expanded={expandedRows.has(row.id)}
                onToggle={() => toggleRow(row.id)}
                onExpandValue={setDialog}
              />
            ))}
          </Box>
        </Box>
      </Box>

      <TablePager
        page={page}
        pageSize={pageSize}
        rowCount={total}
        onPageChange={onPageChange}
        onPageSizeChange={onPageSizeChange}
      />
      <ExpandedValueDialog
        state={dialog}
        onClose={() => setDialog(null)}
        renderAttributeList={(a) => (
          <AttributeList attributes={a} inlineExpand />
        )}
      />
    </>
  );
};

export default LogTable;
