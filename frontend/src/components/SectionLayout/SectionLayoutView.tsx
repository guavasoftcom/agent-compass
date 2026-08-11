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
