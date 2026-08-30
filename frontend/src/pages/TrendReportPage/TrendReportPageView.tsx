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
import {
  Box,
  Chip,
  Paper,
  Skeleton,
  Stack,
  Typography,
  alpha,
  useTheme,
} from '@mui/material';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import { groupForPath } from '../../App/navGroups';
import { auroraColors } from '../../theme/colors';
import { fontFamilies } from '../../theme/typography';
import type { WindowSelection } from '../../api';
import type { WindowOption } from '../../lib/constants';
import MetricRow from './components/MetricRow';
import MetricRowSkeleton from './components/MetricRowSkeleton';
import SectionHeader from './components/SectionHeader';
import type {
  TrendReport,
  TrendMetric,
  TrendMetricKey,
} from './trendReportApi';
import {
  METRIC_LABELS,
  TREND_SECTIONS,
  buildSummaryCallouts,
  computeBeforeSharePct,
  computeDelta,
  describeWindowSpan,
  formatComparingFromDate,
  formatMetricValue,
  formatPeriod,
  type FormattedPeriod,
  type TrendSectionDefinition,
  type TrendSectionKey,
} from './trendReportDerivations';

/** One section's independent query state — Cost, Token efficiency, Reliability, and Activity
 *  each load, error, and render on their own timeline rather than sharing one page-wide state. */
export interface TrendSectionState {
  data: TrendReport | undefined;
  isLoading: boolean;
  error: Error | null;
}

export interface TrendReportPageViewProps {
  selection: WindowSelection;
  onSelectionChange: (selection: WindowSelection) => void;
  windows: readonly WindowOption[];
  onReload: () => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (autoRefresh: boolean) => void;
  isPolling: boolean;
  sections: Record<TrendSectionKey, TrendSectionState>;
}

const CenterIcon = ({ children }: { children: ReactNode }) => (
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={2.5}
    width={12}
    height={12}
  >
    {children}
  </svg>
);

const SECTION_ICONS: Record<TrendSectionKey, ReactNode> = {
  cost: (
    <CenterIcon>
      <line x1="12" y1="1" x2="12" y2="23" />
      <path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6" />
    </CenterIcon>
  ),
  tokenEfficiency: (
    <CenterIcon>
      <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
    </CenterIcon>
  ),
  reliability: (
    <CenterIcon>
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
    </CenterIcon>
  ),
  activity: (
    <CenterIcon>
      <polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />
    </CenterIcon>
  ),
};

/** Trend Report metric rows never fetch on their own — this view builds every row's props from
 *  whichever section's `metrics` bundle the container already fetched. */
const buildRowProps = (metricKey: TrendMetricKey, trend: TrendMetric) => {
  const labels = METRIC_LABELS[metricKey];
  const delta = computeDelta(metricKey, trend);
  return {
    label: labels.name,
    before: {
      value: formatMetricValue(metricKey, trend.before),
      sub: labels.sub,
      series: trend.beforeSeries,
    },
    after: {
      value: formatMetricValue(metricKey, trend.after),
      sub: labels.sub,
      series: trend.afterSeries,
    },
    state: delta.state,
    direction: delta.direction,
    deltaLabel: delta.label,
    beforeSharePct: computeBeforeSharePct(trend.before, trend.after),
  };
};

/**
 * Renders one section's body from its own independent query state — skeleton rows while it's
 * still loading with nothing to show yet, a short inline message if it errored with nothing to
 * show, or its real `MetricRow`s once data has arrived. This is what lets a slow or failed
 * section render on its own timeline instead of waiting on, or blocking, its siblings.
 */
const renderSectionBody = (section: TrendSectionDefinition, sectionState: TrendSectionState): ReactNode => {
  if (sectionState.isLoading && !sectionState.data) {
    return section.metricKeys.map((metricKey) => <MetricRowSkeleton key={metricKey} />);
  }

  if (sectionState.error && !sectionState.data) {
    return (
      <Box sx={{ px: 3, py: 2 }}>
        <Typography variant="body2" sx={{ color: 'error.main' }}>
          Couldn&apos;t load this section.
        </Typography>
      </Box>
    );
  }

  if (!sectionState.data) {
    return null;
  }

  const data = sectionState.data;
  return section.metricKeys.map((metricKey) => {
    const trend = data.metrics[metricKey];
    if (!trend) {
      return null;
    }
    const rowProps = buildRowProps(metricKey, trend);
    return <MetricRow key={metricKey} {...rowProps} />;
  });
};

const TrendReportPageView = ({
  selection,
  onSelectionChange,
  windows,
  onReload,
  autoRefresh,
  onAutoRefreshChange,
  isPolling,
  sections,
}: TrendReportPageViewProps) => {
  const theme = useTheme();
  const isDarkMode = theme.palette.mode === 'dark';
  // Mirrors the reference design's literal per-mode CSS custom properties
  // (--before-bg/--after-bg/--range in Aurora Trends Report v2.html) rather than one flat
  // alpha value across both modes — the design deliberately uses a weaker wash in dark mode
  // (2%/6%) than light mode (3%/4%) so the columns stay barely-there against the darker card.
  const beforeColumnWash = alpha(
    isDarkMode ? theme.palette.common.white : theme.palette.text.primary,
    isDarkMode ? 0.02 : 0.03,
  );
  const afterColumnWash = alpha(
    theme.palette.primary.main,
    isDarkMode ? 0.06 : 0.04,
  );
  const rangeTint = alpha(theme.palette.primary.main, isDarkMode ? 0.16 : 0.1);

  const sectionAccent: Record<TrendSectionKey, string> = {
    cost: auroraColors.gold,
    tokenEfficiency: theme.palette.primary.main,
    reliability: theme.palette.success.main,
    activity: auroraColors.cyan,
  };

  const windowSpanLabel = describeWindowSpan(selection, windows);

  // All four sections compute identical window boundaries from the same request, so the period
  // bar and "Comparing from" pill can read off whichever section resolved first.
  const anyLoadedReport = TREND_SECTIONS.map((section) => sections[section.key].data).find(Boolean);
  const showSkeleton = !anyLoadedReport && TREND_SECTIONS.every((section) => sections[section.key].isLoading);
  const comparingFromDate = anyLoadedReport ? formatComparingFromDate(anyLoadedReport.current) : null;
  const beforePeriod: FormattedPeriod = anyLoadedReport
    ? formatPeriod(anyLoadedReport.previous)
    : { primary: '—', secondary: null };
  const afterPeriod: FormattedPeriod = anyLoadedReport
    ? formatPeriod(anyLoadedReport.current)
    : { primary: '—', secondary: null };

  // buildSummaryCallouts already tolerates a partial metrics map — merging whatever sections
  // have loaded so far means callouts appear incrementally (Cost's total_cost, Reliability's
  // tool_errors, Activity's sessions) rather than waiting for every section to resolve.
  const mergedMetrics = TREND_SECTIONS.reduce<Partial<Record<TrendMetricKey, TrendMetric>>>(
    (accumulated, section) => ({ ...accumulated, ...sections[section.key].data?.metrics }),
    {},
  );
  const summaryCallouts = buildSummaryCallouts(mergedMetrics);

  // A page-wide error banner only makes sense once every section has failed with nothing to
  // show — otherwise each failed section renders its own inline message below, and the page
  // still has real content from its siblings.
  const allSectionsErroredWithNoData = TREND_SECTIONS.every(
    (section) => sections[section.key].error && !sections[section.key].data,
  );
  const pageError = allSectionsErroredWithNoData ? sections[TREND_SECTIONS[0].key].error : null;

  return (
    <PageLayout
      eyebrow={groupForPath('/trend-report')}
      title={
        <Stack
          direction="row"
          spacing={1.5}
          sx={{ alignItems: 'center', flexWrap: 'wrap' }}
          useFlexGap
        >
          <span>Trend Report</span>
          {showSkeleton && (
            <Skeleton variant="rounded" width={190} height={26} sx={{ borderRadius: 999 }} />
          )}
          {comparingFromDate && (
            <Chip
              size="small"
              label={
                <Box component="span" sx={{ fontSize: 12, fontWeight: 600 }}>
                  Comparing from{' '}
                  <Box component="span" sx={{ fontWeight: 700 }}>
                    {comparingFromDate}
                  </Box>
                </Box>
              }
              sx={{
                backgroundColor: alpha(theme.palette.primary.main, 0.1),
                border: `1px solid ${alpha(theme.palette.primary.main, 0.22)}`,
                color: 'primary.main',
                height: 26,
              }}
            />
          )}
        </Stack>
      }
      subtitle={`Side-by-side diff of the ${windowSpanLabel} before vs. after — green means improved.`}
      actions={
        <PageActions
          selection={selection}
          onSelectionChange={onSelectionChange}
          windows={windows}
          onReload={onReload}
          autoRefresh={autoRefresh}
          onAutoRefreshChange={onAutoRefreshChange}
          isPolling={isPolling}
        />
      }
      error={pageError}
    >
      <Paper
        variant="outlined"
        sx={{ p: 0, position: 'relative', overflow: 'hidden' }}
      >
        {/* Alternating column washes for the WHOLE card — a single absolutely-positioned overlay
            behind every row (period bar, section headers, metric rows), not per-row bgcolor. The
            overlay uses the same 3-column template as every row below it so the column
            boundaries line up exactly. */}
        <Box
          aria-hidden
          sx={{
            position: 'absolute',
            inset: 0,
            display: 'grid',
            gridTemplateColumns: '1fr 160px 1fr',
            zIndex: 0,
            pointerEvents: 'none',
          }}
        >
          <Box sx={{ backgroundColor: beforeColumnWash }} />
          <Box />
          <Box sx={{ backgroundColor: afterColumnWash }} />
        </Box>

        <Box sx={{ position: 'relative', zIndex: 1 }}>
          {/* Period bar */}
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: '1fr 160px 1fr',
              borderBottom: `1px solid ${theme.palette.divider}`,
            }}
          >
            <Box sx={{ px: 3, py: 2.25, textAlign: 'right' }}>
              <Typography
                sx={{ typography: 'eyebrowSm', color: 'text.secondary' }}
              >
                Before
              </Typography>
              {showSkeleton ? (
                <>
                  <Skeleton variant="text" width={110} height={22} sx={{ ml: 'auto', mt: 0.4 }} />
                  <Skeleton variant="text" width={84} height={16} sx={{ ml: 'auto' }} />
                </>
              ) : (
                <>
                  <Typography
                    sx={{
                      fontFamily: fontFamilies.display,
                      fontWeight: 700,
                      fontSize: 15,
                      mt: 0.4,
                    }}
                  >
                    {beforePeriod.primary}
                  </Typography>
                  {beforePeriod.secondary && (
                    <Typography
                      sx={{
                        fontFamily: fontFamilies.display,
                        fontWeight: 500,
                        fontSize: 12.5,
                        color: 'text.secondary',
                        mt: 0.15,
                      }}
                    >
                      {beforePeriod.secondary}
                    </Typography>
                  )}
                </>
              )}
            </Box>
            <Box
              sx={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 0.5,
                borderLeft: `1px solid ${theme.palette.divider}`,
                borderRight: `1px solid ${theme.palette.divider}`,
              }}
            >
              <Typography
                sx={{
                  fontSize: 10,
                  fontWeight: 700,
                  letterSpacing: 1.2,
                  textTransform: 'uppercase',
                  color: 'text.disabled',
                }}
              >
                vs
              </Typography>
              <Box
                sx={{
                  width: 28,
                  height: 28,
                  borderRadius: '50%',
                  display: 'grid',
                  placeItems: 'center',
                  backgroundColor: alpha(theme.palette.primary.main, 0.1),
                  color: 'primary.main',
                }}
              >
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2.5}
                  width={14}
                  height={14}
                >
                  <path d="M5 12h14M12 5l7 7-7 7" />
                </svg>
              </Box>
            </Box>
            <Box sx={{ px: 3, py: 2.25, textAlign: 'left' }}>
              <Typography
                sx={{ typography: 'eyebrowSm', color: 'text.secondary' }}
              >
                After
              </Typography>
              {showSkeleton ? (
                <>
                  <Skeleton variant="text" width={110} height={22} sx={{ mt: 0.4 }} />
                  <Skeleton variant="text" width={84} height={16} />
                </>
              ) : (
                <>
                  <Typography
                    sx={{
                      fontFamily: fontFamilies.display,
                      fontWeight: 700,
                      fontSize: 15,
                      mt: 0.4,
                      color: 'primary.main',
                    }}
                  >
                    {afterPeriod.primary}
                  </Typography>
                  {afterPeriod.secondary && (
                    <Typography
                      sx={{
                        fontFamily: fontFamilies.display,
                        fontWeight: 500,
                        fontSize: 12.5,
                        color: 'text.secondary',
                        mt: 0.15,
                      }}
                    >
                      {afterPeriod.secondary}
                    </Typography>
                  )}
                </>
              )}
            </Box>
          </Box>

          {/* Each section independently checks its own sections[section.key] state — a slow or
              failed section renders its own skeleton/error/rows without waiting on, or blocking,
              its siblings. */}
          {TREND_SECTIONS.map((section) => (
            <Box key={section.key}>
              <SectionHeader
                label={section.label}
                accentColor={sectionAccent[section.key]}
                icon={SECTION_ICONS[section.key]}
              />
              {renderSectionBody(section, sections[section.key])}
            </Box>
          ))}

          {showSkeleton && (
            <Box
              sx={{
                p: 3,
                borderTop: `1px solid ${theme.palette.divider}`,
                backgroundColor: rangeTint,
                display: 'flex',
                gap: 4,
                flexWrap: 'wrap',
              }}
            >
              {[0, 1, 2].map((index) => (
                <Box key={index} sx={{ flex: 1, minWidth: 200 }}>
                  <Skeleton variant="text" width={70} height={16} sx={{ mb: 0.5 }} />
                  <Skeleton variant="text" width="90%" height={18} />
                  <Skeleton variant="text" width="70%" height={18} />
                </Box>
              ))}
            </Box>
          )}

          {summaryCallouts.length > 0 && (
            <Box
              sx={{
                p: 3,
                borderTop: `1px solid ${theme.palette.divider}`,
                backgroundColor: rangeTint,
                display: 'flex',
                gap: 4,
                flexWrap: 'wrap',
              }}
            >
              {summaryCallouts.map((callout) => (
                <Box key={callout.label} sx={{ flex: 1, minWidth: 200 }}>
                  <Typography
                    sx={{
                      fontSize: 11,
                      fontWeight: 700,
                      letterSpacing: 0.5,
                      textTransform: 'uppercase',
                      color: 'text.secondary',
                      mb: 0.5,
                    }}
                  >
                    {callout.label}
                  </Typography>
                  <Typography
                    sx={{
                      fontSize: 13,
                      color: 'text.primary',
                      lineHeight: 1.5,
                    }}
                  >
                    {callout.text}
                  </Typography>
                </Box>
              ))}
            </Box>
          )}
        </Box>
      </Paper>
    </PageLayout>
  );
};

export default TrendReportPageView;
