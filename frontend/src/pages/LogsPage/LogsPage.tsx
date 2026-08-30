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
import { useEffect, useMemo } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { AUTO_REFRESH_INTERVAL_MS, WINDOWS } from '../../lib/constants';
import { resolveWindow } from '../../lib/resolveWindow';
import { useWindowContext } from '../../lib/windowContext';
import LogsPageView from './LogsPageView';

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
