/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
import { createContext, useContext, useEffect, useMemo } from 'react';
import type { ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { AUTO_REFRESH_INTERVAL_MS, WINDOWS } from '../../lib/constants';
import type { WindowOption } from '../../lib/constants';
import { resolveWindow } from '../../lib/resolveWindow';
import { useWindowContext } from '../../lib/windowContext';
import type { WindowSelection } from '../../api';
import useTracesExplorer, { type TracesExplorer } from './useTracesExplorer';

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
