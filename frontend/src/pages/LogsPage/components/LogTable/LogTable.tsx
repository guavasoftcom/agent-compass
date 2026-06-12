import { useEffect, useState } from 'react';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { fetchLogsPage, type LogsFilters } from '../../logsApi';
import LogTableView from './LogTableView';

const DEFAULT_PAGE_SIZE = 50;

export interface LogTableProps {
  /** Called at fetch time to produce fresh timestamps — never captured at render time. */
  resolveFilters: () => LogsFilters;
  /** Stable serialization of the current filter+window state — query key + reset-to-first-page trigger. */
  filtersKey: string;
  /** Report the matching-row total up to the shared events counter. */
  onTotalChange: (total: number) => void;
}

/**
 * Table container — owns offset paging (page + size) and its own offset-paged query.
 * Only mounted in Table view, so the query runs whenever it is visible. Renders the
 * presentational LogTableView. Never polls — live tail and auto-refresh are
 * Stream-only; the table refetches on filter/window changes and manual reload.
 *
 * `placeholderData: keepPreviousData` means page flips and background refetches
 * repaint in place rather than blanking the table between fetches.
 */
const LogTable = ({ resolveFilters, filtersKey, onTotalChange }: LogTableProps) => {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  // A new filter set resets back to the first page. Done in render (not an effect) so
  // it lands before the query fires this render and preserves the chosen pageSize.
  const [prevFiltersKey, setPrevFiltersKey] = useState(filtersKey);
  if (prevFiltersKey !== filtersKey) {
    setPrevFiltersKey(filtersKey);
    setPage(0);
  }

  const query = useQuery({
    queryKey: ['log-table', filtersKey, page, pageSize],
    queryFn: () => fetchLogsPage(resolveFilters(), page, pageSize),
    placeholderData: keepPreviousData,
  });

  const total = query.data?.totalCount ?? 0;
  useEffect(() => {
    onTotalChange(total);
  }, [total, onTotalChange]);

  return (
    <LogTableView
      rows={query.data?.items ?? []}
      total={total}
      page={page}
      pageSize={pageSize}
      loading={query.isLoading}
      onPageChange={setPage}
      onPageSizeChange={(size) => {
        setPageSize(size);
        setPage(0);
      }}
    />
  );
};

export default LogTable;
