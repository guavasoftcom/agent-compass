import { createContext, useContext, useEffect, useMemo } from 'react';
import type { ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { WINDOWS } from '../../constants';
import type { WindowOption } from '../../constants';
import { useWindowContext } from '../../windowContext';
import type { WindowSelection } from '../../api';
import useTracesExplorer, { type TracesExplorer } from './useTracesExplorer';

const AUTO_REFRESH_INTERVAL_MS = 60_000;
const MS_PER_MINUTE = 60_000;

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
  const label = WINDOWS.find((option) => option.value === selection.minutes)?.label ?? 'window';
  return {
    startTimestamp: new Date(now - selection.minutes * MS_PER_MINUTE).toISOString(),
    endTimestamp: new Date(now + MS_PER_MINUTE).toISOString(),
    label,
  };
};

// The view and every page-specific trace component read their data and handlers
// from this context, so the page tree shares one useTracesExplorer instance
// instead of drilling its (large) result set down through props.
export interface TracesExplorerContextValue extends TracesExplorer {
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  windows: readonly WindowOption[];
  windowLabel: string;
  error: Error | null;
  onReload: () => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (next: boolean) => void;
  isPolling: boolean;
}

const TracesExplorerContext = createContext<TracesExplorerContextValue | null>(null);

export const useTracesExplorerContext = (): TracesExplorerContextValue => {
  const value = useContext(TracesExplorerContext);
  if (!value) {
    throw new Error('useTracesExplorerContext must be used within a TracesExplorerProvider');
  }
  return value;
};

export const TracesExplorerProvider = ({ children }: { children: ReactNode }) => {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();
  const queryClient = useQueryClient();
  const resolved = useMemo(() => resolveWindow(selection), [selection]);

  const explorer = useTracesExplorer({
    startTimestamp: resolved.startTimestamp,
    endTimestamp: resolved.endTimestamp,
    autoRefresh,
    onAutoRefreshChange: setAutoRefresh,
  });

  const reloadTraces = () => {
    void queryClient.invalidateQueries({ queryKey: ['trace-histogram'] });
    void queryClient.invalidateQueries({ queryKey: ['trace-facets'] });
    void queryClient.invalidateQueries({ queryKey: ['trace-table'] });
  };

  const isPresetPolling = autoRefresh && selection.kind === 'preset';
  useEffect(() => {
    if (!isPresetPolling) {
      return undefined;
    }
    const interval = window.setInterval(reloadTraces, AUTO_REFRESH_INTERVAL_MS);
    return () => window.clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isPresetPolling]);

  const handleSelectionChange = (next: WindowSelection) => {
    explorer.clearZoom();
    setSelection(next);
  };

  const value: TracesExplorerContextValue = {
    ...explorer,
    selection,
    onSelectionChange: handleSelectionChange,
    windows: WINDOWS,
    windowLabel: resolved.label,
    error: null,
    onReload: reloadTraces,
    autoRefresh,
    onAutoRefreshChange: setAutoRefresh,
    isPolling: false,
  };

  return <TracesExplorerContext.Provider value={value}>{children}</TracesExplorerContext.Provider>;
};
