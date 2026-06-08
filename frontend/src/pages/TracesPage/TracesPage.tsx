import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchTraces, type WindowSelection } from '../../api';
import { useWindowContext } from '../../windowContext';

const AUTO_REFRESH_INTERVAL_MS = 60_000;
import TracesPageView, {
  type PaginationModel,
  type TraceGridRow,
} from './TracesPageView';

const DEFAULT_PAGE_SIZE = 50;

export default function TracesPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();
  const [paginationModel, setPaginationModel] = useState<PaginationModel>({
    pageSize: DEFAULT_PAGE_SIZE,
    page: 0,
  });

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const refetchInterval =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['traces', selectionKey],
    queryFn: () => fetchTraces(selection),
    refetchInterval,
  });

  const rows: TraceGridRow[] = (data?.items ?? []).map((trace) => ({
    ...trace,
    id: trace.traceId,
  }));

  const handleReload = () => {
    setPaginationModel((previous) => ({ ...previous, page: 0 }));
    refetch();
  };

  const handleSelectionChange = (next: WindowSelection) => {
    setPaginationModel((previous) => ({ ...previous, page: 0 }));
    setSelection(next);
  };

  return (
    <TracesPageView
      rows={rows}
      isLoading={isLoading}
      error={error as Error | null}
      paginationModel={paginationModel}
      onPaginationModelChange={setPaginationModel}
      onReload={handleReload}
      selection={selection}
      onSelectionChange={handleSelectionChange}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={setAutoRefresh}
      isPolling={autoRefresh && selection.kind === 'preset' && isFetching}
    />
  );
}
