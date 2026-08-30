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
import { Outlet } from 'react-router-dom';
import { Box } from '@mui/material';
import PageLayout from '../PageLayout';
import PageActions from '../PageActions';
import PillTabs from '../PillTabs';
import { WINDOWS } from '../../lib/constants';
import type { WindowSelection } from '../../api';
import type { SectionTab, SectionContextValue } from './SectionLayout';

export interface SectionLayoutViewProps {
  eyebrow?: string;
  title: string;
  subtitle?: string;
  tabs: readonly SectionTab[];
  selection: WindowSelection;
  onSelectionChange: (selection: WindowSelection) => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (value: boolean) => void;
  isPolling: boolean;
  onReload: () => void;
  activeTab: string;
  context: SectionContextValue;
}

export const SectionLayoutView = ({
  eyebrow,
  title,
  subtitle,
  tabs,
  selection,
  onSelectionChange,
  autoRefresh,
  onAutoRefreshChange,
  isPolling,
  onReload,
  activeTab,
  context,
}: SectionLayoutViewProps) => {
  return (
    <PageLayout
      eyebrow={eyebrow}
      title={title}
      subtitle={subtitle}
      actions={
        <PageActions
          selection={selection}
          onSelectionChange={onSelectionChange}
          windows={WINDOWS}
          onReload={onReload}
          autoRefresh={autoRefresh}
          onAutoRefreshChange={onAutoRefreshChange}
          isPolling={isPolling}
        />
      }
    >
      {/* Aurora pill tabs — routed form: each tab navigates to its own child route */}
      <PillTabs
        tabs={tabs.map((tab) => ({ value: tab.to, label: tab.label, to: tab.to }))}
        activeValue={activeTab}
        ariaLabel={`${title} views`}
      />

      <Box sx={{ mt: 0.5 }}>
        <Outlet context={context} />
      </Box>
    </PageLayout>
  );
};
