import { useQueryClient } from '@tanstack/react-query';
import { WINDOWS } from '../../constants';
import { useWindowContext } from '../../windowContext';
import LogsPageView from './LogsPageView';

export default function LogsPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();
  const queryClient = useQueryClient();

  // Manual reload: invalidate all four query keys so TanStack Query performs a
  // background refetch. With stable keys the cache data stays on screen during
  // the fetch — no empty-state flash.
  const reloadLogs = () => {
    void queryClient.invalidateQueries({ queryKey: ['log-histogram'] });
    void queryClient.invalidateQueries({ queryKey: ['log-facets'] });
    void queryClient.invalidateQueries({ queryKey: ['log-table'] });
    void queryClient.invalidateQueries({ queryKey: ['log-stream'] });
  };

  const windowLabel = selection.kind === 'preset'
    ? (WINDOWS.find((w) => w.value === selection.minutes)?.label ?? 'window')
    : 'selected range';

  return (
    <LogsPageView
      selection={selection}
      onSelectionChange={setSelection}
      windows={WINDOWS}
      windowLabel={windowLabel}
      error={null}
      onReload={reloadLogs}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={setAutoRefresh}
      isPolling={false}
    />
  );
}
