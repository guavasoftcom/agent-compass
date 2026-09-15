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
import type { ReactNode } from 'react';
import type { WindowSelection } from '../../api';
import type { WindowOption } from '../../lib/constants';
import RepositorySelector from '../RepositorySelector';
import WindowSelector from '../WindowSelector';
import PageActionsView from './PageActionsView';

export interface PageActionsProps {
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  windows?: readonly WindowOption[];
  onReload?: () => void;
  hideReload?: boolean;
  autoRefresh?: boolean;
  onAutoRefreshChange?: (next: boolean) => void;
  isPolling?: boolean;
  hideAutoRefresh?: boolean;
  /** Force auto-refresh off + disabled regardless of preset state (e.g. Logs is zoomed into a bucket). */
  autoRefreshDisabled?: boolean;
  extraActions?: ReactNode;
  /**
   * `RepositorySelector`'s current value + change handler, wired to
   * `WindowContextValue.repositoryUrl`/`setRepositoryUrl`. A single object rather than
   * two independently-optional props, so "value present, handler absent" (or vice
   * versa) isn't representable — the selector renders exactly when this is passed.
   * Omitted entirely (not just `{ value: null, ... }`) hides the selector — opt-in per
   * page while a page's backend slice doesn't yet honor `repositoryUrl`, per the
   * repository-attribution design doc's staged rollout.
   */
  repositorySelector?: {
    value: string | null;
    onChange: (next: string | null) => void;
  };
}

const noop = () => {};

const PageActions = ({
  selection,
  onSelectionChange,
  windows,
  onReload,
  hideReload = false,
  autoRefresh = false,
  onAutoRefreshChange,
  isPolling = false,
  hideAutoRefresh = false,
  autoRefreshDisabled = false,
  extraActions,
  repositorySelector,
}: PageActionsProps) => {
  // Both Refresh and Auto-refresh are tied to "preset" mode: a custom range has a fixed
  // end, so re-fetching can't surface new data and polling would be wasted requests.
  const presetActive = selection.kind === 'preset';
  const autoRefreshActive = autoRefresh && presetActive && !autoRefreshDisabled;

  return (
    <PageActionsView
      windowSelector={
        <WindowSelector
          selection={selection}
          onSelectionChange={onSelectionChange}
          windows={windows}
        />
      }
      repositorySelector={
        repositorySelector != null ? (
          <RepositorySelector
            value={repositorySelector.value}
            onValueChange={repositorySelector.onChange}
          />
        ) : undefined
      }
      extraActions={extraActions}
      onReload={onReload ?? noop}
      reloadDisabled={!presetActive || onReload == null}
      hideReload={hideReload}
      autoRefreshActive={autoRefreshActive}
      autoRefreshDisabled={!presetActive || onAutoRefreshChange == null || autoRefreshDisabled}
      isPolling={isPolling}
      onToggleAutoRefresh={() =>
        onAutoRefreshChange?.(!autoRefresh)
      }
      hideAutoRefresh={hideAutoRefresh}
    />
  );
};

export default PageActions;
