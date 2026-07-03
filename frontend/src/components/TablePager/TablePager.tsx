import { Box } from '@mui/material';
import type { SxProps, Theme } from '@mui/material/styles';
import SegmentedToggle from '../SegmentedToggle';
import { PAGE_SIZE_OPTIONS } from '../../lib/constants';
import { radii } from '../../theme/theme';

// Square icon button style for the pager prev/next chevrons.
const pagerNavSx: SxProps<Theme> = {
  width: 32,
  height: 32,
  display: 'grid',
  placeItems: 'center',
  borderRadius: radii.sm,
  border: 1,
  borderColor: 'divider',
  bgcolor: 'background.paper',
  color: 'text.secondary',
  cursor: 'pointer',
  transition: 'all .12s',
  '&:hover:not(:disabled)': {
    color: 'primary.main',
    borderColor: 'primary.main',
  },
  '&:disabled': { opacity: 0.4, cursor: 'default' },
  '& svg': { width: 15, height: 15 },
};

export interface TablePagerProps {
  page: number;
  pageSize: number;
  rowCount: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (pageSize: number) => void;
}

// Shared pager footer for all offset-paged tables (Sessions, Logs, Traces).
// Renders the rows-per-page SegmentedToggle, the N–M of total range label,
// and prev/next chevron navigation. All internal derivations (startIndex,
// maxPage, rangeLabel) are computed here so call sites stay clean.
const TablePager = ({
  page,
  pageSize,
  rowCount,
  onPageChange,
  onPageSizeChange,
}: TablePagerProps) => {
  const startIndex = page * pageSize;
  const maxPage = Math.max(0, Math.ceil(rowCount / pageSize) - 1);
  const rangeLabel =
    rowCount === 0
      ? '0 of 0'
      : `${(startIndex + 1).toLocaleString()}–${Math.min(startIndex + pageSize, rowCount).toLocaleString()} of ${rowCount.toLocaleString()}`;

  return (
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
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          fontSize: 12.5,
          color: 'text.secondary',
        }}
      >
        Rows per page
        <SegmentedToggle
          options={PAGE_SIZE_OPTIONS.map((size) => ({
            value: size,
            label: size,
          }))}
          value={pageSize}
          onChange={onPageSizeChange}
        />
      </Box>
      <Box
        sx={{
          fontSize: 12.5,
          color: 'text.secondary',
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        <Box component="span" sx={{ color: 'text.primary', fontWeight: 600 }}>
          {rangeLabel}
        </Box>
      </Box>
      <Box sx={{ display: 'flex', gap: 0.75 }}>
        <Box
          component="button"
          type="button"
          disabled={page <= 0}
          onClick={() => onPageChange(page - 1)}
          sx={pagerNavSx}
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
          >
            <path d="M15 6l-6 6 6 6" />
          </svg>
        </Box>
        <Box
          component="button"
          type="button"
          disabled={page >= maxPage}
          onClick={() => onPageChange(page + 1)}
          sx={pagerNavSx}
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
          >
            <path d="M9 6l6 6-6 6" />
          </svg>
        </Box>
      </Box>
    </Box>
  );
};

export default TablePager;
