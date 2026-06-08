import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  fetchLogAttributeKeys,
  fetchLogAttributeValues,
  fetchLogs,
  type TimeWindow,
  type WindowSelection,
} from '../../api';
import { useWindowContext } from '../../windowContext';
import LogsPageView, { type PaginationModel } from './LogsPageView';
import BodyDialog from './BodyDialog';

const DEFAULT_PAGE_SIZE = 50;
const MS_PER_MINUTE = 60 * 1000;
const AUTO_REFRESH_INTERVAL_MS = 60_000;

const selectionToTimeWindow = (selection: WindowSelection): TimeWindow => {
  if (selection.kind === 'custom') {
    return {
      startTimestamp: selection.startTimestamp,
      endTimestamp: selection.endTimestamp,
    };
  }
  return {
    startTimestamp: new Date(
      Date.now() - selection.minutes * MS_PER_MINUTE,
    ).toISOString(),
  };
};

export default function LogsPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();
  const [selectedFilters, setSelectedFilters] = useState<string[]>([]);
  const [autocompleteInput, setAutocompleteInput] = useState<string>('');
  const [openBody, setOpenBody] = useState<string | null>(null);
  const [paginationModel, setPaginationModel] = useState<PaginationModel>({
    pageSize: DEFAULT_PAGE_SIZE,
    page: 0,
  });

  // The Logs API still takes a TimeWindow (start / optional end) rather than a
  // WindowSelection. Derive it from the shared selector so cache keys and
  // autocomplete queries see the same boundary.
  const window = useMemo<TimeWindow>(
    () => selectionToTimeWindow(selection),
    [selection],
  );

  const windowKey = `${window.startTimestamp ?? ''}|${window.endTimestamp ?? ''}`;

  const refetchInterval =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  const { data, isLoading, error, isFetching, refetch } = useQuery({
    queryKey: ['logs', selectedFilters, windowKey],
    queryFn: () => fetchLogs(selectedFilters, window),
    refetchInterval,
  });

  // Two-stage attribute autocomplete: when the user hasn't typed `=` yet we show distinct
  // keys; once `key=` is present we swap to values for that key. Splitting on the first `=`
  // matches how filters round-trip — values can contain `=`, keys cannot.
  const partialKey = useMemo<string | null>(() => {
    const eqIndex = autocompleteInput.indexOf('=');
    return eqIndex === -1 ? null : autocompleteInput.slice(0, eqIndex);
  }, [autocompleteInput]);

  const { data: attributeKeys = [] } = useQuery({
    queryKey: ['log-attribute-keys', selectedFilters, windowKey],
    queryFn: () => fetchLogAttributeKeys(selectedFilters, window),
  });

  const { data: attributeValues = [] } = useQuery({
    queryKey: [
      'log-attribute-values',
      partialKey,
      selectedFilters,
      windowKey,
    ],
    queryFn: () =>
      fetchLogAttributeValues(partialKey ?? '', selectedFilters, window),
    enabled: partialKey != null && partialKey.length > 0,
  });

  const autocompleteOptions = useMemo<string[]>(() => {
    if (partialKey == null) {
      return attributeKeys.map((key) => `${key}=`);
    }
    return attributeValues.map((value) => `${partialKey}=${value}`);
  }, [partialKey, attributeKeys, attributeValues]);

  const handleSelectionChange = (next: WindowSelection) => {
    setPaginationModel((previous) => ({ ...previous, page: 0 }));
    setSelection(next);
  };

  return (
    <>
      <LogsPageView
        rows={data?.items ?? []}
        isLoading={isLoading}
        error={error as Error | null}
        paginationModel={paginationModel}
        onPaginationModelChange={setPaginationModel}
        selectedFilters={selectedFilters}
        onSelectedFiltersChange={setSelectedFilters}
        autocompleteOptions={autocompleteOptions}
        autocompleteInput={autocompleteInput}
        onAutocompleteInputChange={setAutocompleteInput}
        selection={selection}
        onSelectionChange={handleSelectionChange}
        onReload={refetch}
        autoRefresh={autoRefresh}
        onAutoRefreshChange={setAutoRefresh}
        isPolling={autoRefresh && selection.kind === 'preset' && isFetching}
        onOpenBody={setOpenBody}
      />
      <BodyDialog body={openBody} onClose={() => setOpenBody(null)} />
    </>
  );
}
