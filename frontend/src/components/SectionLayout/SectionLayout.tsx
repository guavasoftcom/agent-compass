import { useLocation, useOutletContext } from 'react-router-dom';
import { useIsFetching, useQueryClient } from '@tanstack/react-query';
import { useWindowContext } from '../../windowContext';
import { groupForPath } from '../../App/navItems';
import type { WindowSelection } from '../../api';
import { SectionLayoutView } from './SectionLayoutView';

export interface SectionTab {
  to: string;
  label: string;
}

export interface SectionLayoutProps {
  title: string;
  subtitle?: string;
  tabs: readonly SectionTab[];
  queryKeyPrefixes: readonly string[];
}

export interface SectionContextValue {
  selection: WindowSelection;
  autoRefresh: boolean;
}

const SectionLayout = ({
  title,
  subtitle,
  tabs,
  queryKeyPrefixes,
}: SectionLayoutProps) => {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();
  const location = useLocation();
  const queryClient = useQueryClient();

  const matchesSection = (queryKey: readonly unknown[]): boolean =>
    typeof queryKey[0] === 'string'
    && queryKeyPrefixes.includes(queryKey[0]);

  const fetchingCount = useIsFetching({
    predicate: (query) => matchesSection(query.queryKey),
  });
  const isPolling =
    autoRefresh && selection.kind === 'preset' && fetchingCount > 0;

  const handleReload = () => {
    queryClient.invalidateQueries({
      predicate: (query) => matchesSection(query.queryKey),
    });
  };

  const activeTab =
    [...tabs]
      .sort((leftTab, rightTab) => rightTab.to.length - leftTab.to.length)
      .find((tab) => location.pathname.startsWith(tab.to))?.to ?? tabs[0].to;

  // Eyebrow above the title: just the nav group name (e.g. "Usage").
  const groupHeading = groupForPath(location.pathname);
  const eyebrow = groupHeading;

  const context: SectionContextValue = { selection, autoRefresh };

  return (
    <SectionLayoutView
      eyebrow={eyebrow || undefined}
      title={title}
      subtitle={subtitle}
      tabs={tabs}
      selection={selection}
      onSelectionChange={setSelection}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={setAutoRefresh}
      isPolling={isPolling}
      onReload={handleReload}
      activeTab={activeTab}
      context={context}
    />
  );
};

export const useSectionContext = (): SectionContextValue =>
  useOutletContext<SectionContextValue>();

export default SectionLayout;
