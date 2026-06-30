import { Link as RouterLink, Outlet } from 'react-router-dom';
import { alpha, Box, ButtonBase, Stack } from '@mui/material';
import { neutralColors } from '../../theme/colors';
import PageLayout from '../PageLayout';
import PageActions from '../PageActions';
import { WINDOWS } from '../../lib/constants';
import type { WindowSelection } from '../../api';
import type { SectionTab, SectionContextValue } from './SectionLayout';
import { fontFamilies } from '../../theme/typography';

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
      {/* Aurora pill tabs */}
      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
        {tabs.map((tab) => {
          const selected = tab.to === activeTab;
          return (
            <ButtonBase
              key={tab.to}
              component={RouterLink}
              to={tab.to}
              focusRipple
              sx={(theme) => ({
                px: 2,
                py: 1,
                borderRadius: 999,
                fontFamily: fontFamilies.display,
                fontSize: 14,
                fontWeight: 600,
                lineHeight: 1,
                letterSpacing: 0.1,
                border: '1px solid',
                borderColor: selected ? 'divider' : 'transparent',
                color: selected ? 'text.primary' : 'text.secondary',
                backgroundColor: selected
                  ? theme.palette.mode === 'dark'
                    ? alpha(neutralColors.white, 0.08)
                    : alpha(neutralColors.white, 0.9)
                  : 'transparent',
                boxShadow: selected
                  ? theme.palette.mode === 'dark'
                    ? `inset 0 1px 0 ${alpha(neutralColors.white, 0.07)}`
                    : `0 1px 2px ${alpha(neutralColors.inkLight, 0.06)}`
                  : 'none',
                transition: theme.transitions.create([
                  'background-color',
                  'color',
                  'border-color',
                ]),
                '&:hover': {
                  color: 'text.primary',
                  backgroundColor: selected
                    ? undefined
                    : theme.palette.action.hover,
                },
              })}
            >
              {tab.label}
            </ButtonBase>
          );
        })}
      </Stack>

      <Box sx={{ mt: 0.5 }}>
        <Outlet context={context} />
      </Box>
    </PageLayout>
  );
};
