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
import { useLocation, useOutletContext } from 'react-router-dom';
import { useIsFetching, useQueryClient } from '@tanstack/react-query';
import { useWindowContext } from '../../lib/windowContext';
import { groupForPath } from '../../App/navGroups';
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
