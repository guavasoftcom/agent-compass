import type { ReactNode } from 'react';
import type { WindowSelection } from '../../api';
import type { WindowOption } from '../../constants';
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
  extraActions?: ReactNode;
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
  extraActions,
}: PageActionsProps) => {
  // Both Refresh and Auto-refresh are tied to "preset" mode: a custom range has a fixed
  // end, so re-fetching can't surface new data and polling would be wasted requests.
  const presetActive = selection.kind === 'preset';
  const autoRefreshActive = autoRefresh && presetActive;

  return (
    <PageActionsView
      windowSelector={
        <WindowSelector
          selection={selection}
          onSelectionChange={onSelectionChange}
          windows={windows}
        />
      }
      extraActions={extraActions}
      onReload={onReload ?? noop}
      reloadDisabled={!presetActive || onReload == null}
      hideReload={hideReload}
      autoRefreshActive={autoRefreshActive}
      autoRefreshDisabled={!presetActive || onAutoRefreshChange == null}
      isPolling={isPolling}
      onToggleAutoRefresh={() =>
        onAutoRefreshChange?.(!autoRefresh)
      }
      hideAutoRefresh={hideAutoRefresh}
    />
  );
};

export default PageActions;
