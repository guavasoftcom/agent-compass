import { useEffect, useMemo } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { AUTO_REFRESH_INTERVAL_MS, MS_PER_MINUTE, WINDOWS } from '../../lib/constants';
import { useWindowContext } from '../../lib/windowContext';
import type { WindowSelection } from '../../api';
import LogsPageView from './LogsPageView';

interface ResolvedWindow {
  startTimestamp: string;
  endTimestamp: string;
  label: string;
}

const resolveWindow = (selection: WindowSelection): ResolvedWindow => {
  if (selection.kind === 'custom') {
    return {
      startTimestamp: selection.startTimestamp,
      endTimestamp: selection.endTimestamp,
      label: 'selected range',
    };
  }
  const now = Date.now();
  const label = WINDOWS.find((w) => w.value === selection.minutes)?.label ?? 'window';
  return {
    startTimestamp: new Date(now - selection.minutes * MS_PER_MINUTE).toISOString(),
    endTimestamp: new Date(now + MS_PER_MINUTE).toISOString(),
    label,
  };
};

export default function LogsPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();
  const queryClient = useQueryClient();

  const resolved = useMemo(() => resolveWindow(selection), [selection]);

  const reloadLogs = () => {
    void queryClient.invalidateQueries({ queryKey: ['log-histogram'] });
    void queryClient.invalidateQueries({ queryKey: ['log-facets'] });
    void queryClient.invalidateQueries({ queryKey: ['log-table'] });
  };

  const isPresetPolling = autoRefresh && selection.kind === 'preset';

  useEffect(() => {
    if (!isPresetPolling) {
      return undefined;
    }
    const iv = window.setInterval(reloadLogs, AUTO_REFRESH_INTERVAL_MS);
    return () => window.clearInterval(iv);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isPresetPolling]);

  return (
    <LogsPageView
      selection={selection}
      onSelectionChange={setSelection}
      windows={WINDOWS}
      startTimestamp={resolved.startTimestamp}
      endTimestamp={resolved.endTimestamp}
      windowLabel={resolved.label}
      error={null}
      onReload={reloadLogs}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={setAutoRefresh}
      isPolling={false}
    />
  );
}
