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
import { alpha, Box } from '@mui/material';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import CalendarMonthOutlinedIcon from '@mui/icons-material/CalendarMonthOutlined';
import CloseIcon from '@mui/icons-material/Close';
import type { IdentifierUsageRow } from '../../../../api';
import BreakdownList, { type BreakdownRow } from '../../../../components/BreakdownList';
import PeekDrawer from '../../../../components/PeekDrawer';
import { formatCompact } from '../../../../lib/format';
import { gradients, neutralColors } from '../../../../theme/colors';
import { colorForIndex, radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import type { UsageCalendarDay } from '../../usageCalendarApi';
import {
  formatActiveTime,
  formatCalendarUsd,
  formatDayTitle,
  isActiveDay,
} from '../../usageCalendarDerivations';

export interface UsageDayDrawerProps {
  open: boolean;
  /** The clicked day. Kept set through the slide-out so the content leaves with the panel. */
  date: Date | null;
  /** The day's rollup row; undefined when the range returned none for it. */
  day: UsageCalendarDay | undefined;
  /** Whether the per-day model / skill / subagent lists are still loading. */
  isDetailLoading: boolean;
  modelRows: BreakdownRow[];
  skillUsage: IdentifierUsageRow[];
  subagentUsage: IdentifierUsageRow[];
  onClose: () => void;
  onOpenInMetrics: () => void;
}

const SECTION_TITLE_SX = {
  fontFamily: fontFamilies.display,
  fontSize: 12,
  fontWeight: 600,
  letterSpacing: '0.3px',
  color: 'text.secondary',
} as const;

const MUTED_TEXT_SX = { py: 1.25, fontSize: 12.5, color: 'text.secondary' } as const;

const Stat = ({ label, children, isAccent = false }: { label: string; children: ReactNode; isAccent?: boolean }) => (
  <Box>
    <Box sx={{ typography: 'eyebrowSm', color: 'text.disabled' }}>{label}</Box>
    <Box
      sx={{
        mt: 0.75,
        fontFamily: fontFamilies.display,
        fontSize: 18,
        fontWeight: 800,
        letterSpacing: '-0.3px',
        color: isAccent ? 'primary.main' : 'text.primary',
      }}
    >
      {children}
    </Box>
  </Box>
);

const MetricTile = ({ label, children }: { label: string; children: ReactNode }) => (
  <Box sx={{ px: 1.5, py: 1.25, borderRadius: radii.xs, bgcolor: (t) => t.custom?.progressTrack ?? t.palette.action.hover }}>
    <Box sx={{ typography: 'eyebrowSm', fontSize: 10.5, color: 'text.disabled' }}>{label}</Box>
    <Box sx={{ mt: 0.5, fontFamily: fontFamilies.display, fontSize: 15, fontWeight: 700, color: 'text.primary' }}>
      {children}
    </Box>
  </Box>
);

const NegativeFigure = ({ children }: { children: ReactNode }) => (
  <Box component="span" sx={{ ml: 0.75, color: 'error.main' }}>
    {children}
  </Box>
);

const InvocationList = ({
  rows,
  isLoading,
  emptyText,
}: {
  rows: IdentifierUsageRow[];
  isLoading: boolean;
  emptyText: string;
}) => {
  if (rows.length === 0) {
    return <Box sx={MUTED_TEXT_SX}>{isLoading ? 'Loading…' : emptyText}</Box>;
  }
  return (
    <Box>
      {rows.map((row) => (
        <Box
          key={row.tool}
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 1.25,
            py: 1.1,
            borderBottom: 1,
            borderColor: 'divider',
            fontSize: 13,
          }}
        >
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0, fontWeight: 600 }}>
            <Box component="i" sx={{ width: 8, height: 8, borderRadius: '3px', flexShrink: 0, bgcolor: colorForIndex(2) }} />
            <Box component="span" sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {row.tool}
            </Box>
          </Box>
          <Box
            component="span"
            sx={{
              flexShrink: 0,
              px: 1.1,
              py: '2px',
              borderRadius: '7px',
              fontFamily: fontFamilies.display,
              fontSize: 12,
              fontWeight: 700,
              color: 'text.secondary',
              bgcolor: (t) => t.custom?.progressTrack ?? t.palette.action.hover,
            }}
          >
            {`${row.calls} ${row.calls === 1 ? 'run' : 'runs'}`}
          </Box>
        </Box>
      ))}
    </Box>
  );
};

const DrawerHeader = ({ date, onClose }: { date: Date; onClose: () => void }) => (
  <Box sx={{ px: 2.75, py: 2.5, flexShrink: 0, borderBottom: 1, borderColor: 'divider' }}>
    <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 1.5 }}>
      <Box sx={{ minWidth: 0 }}>
        <Box
          sx={{
            mb: 1,
            fontFamily: fontFamilies.display,
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '1.4px',
            textTransform: 'uppercase',
            color: 'primary.main',
          }}
        >
          Day → Detail
        </Box>
        <Box sx={{ fontSize: 18, fontWeight: 600, color: 'text.primary' }}>{formatDayTitle(date)}</Box>
      </Box>
      <Box
        component="button"
        type="button"
        onClick={onClose}
        aria-label="Close day detail"
        sx={{
          display: 'grid',
          placeItems: 'center',
          width: 32,
          height: 32,
          flexShrink: 0,
          border: 1,
          borderColor: 'divider',
          borderRadius: radii.xs,
          bgcolor: 'background.paper',
          color: 'text.secondary',
          cursor: 'pointer',
          '&:hover': { color: 'error.main', borderColor: (t) => alpha(t.palette.error.main, 0.4) },
        }}
      >
        <CloseIcon sx={{ fontSize: 18 }} />
      </Box>
    </Box>
    <Box sx={{ mt: 1.5, fontSize: 12.5, lineHeight: 1.5, color: 'text.secondary' }}>
      Daily usage snapshot — cost, tokens, and skill/agent activity for this day.
    </Box>
  </Box>
);

/**
 * Right-side quick peek at one calendar day: its headline figures, the four "all metrics" totals,
 * the day's model split and the skills and subagents that ran. Skills and subagents are two lists
 * because they come from two endpoints and are two different things — never one merged list.
 */
const UsageDayDrawer = ({
  open,
  date,
  day,
  isDetailLoading,
  modelRows,
  skillUsage,
  subagentUsage,
  onClose,
  onOpenInMetrics,
}: UsageDayDrawerProps) => {
  const hasActivity = isActiveDay(day);

  return (
    <PeekDrawer open={open} onClose={onClose}>
      {date ? (
        <>
          <DrawerHeader date={date} onClose={onClose} />
          {day && hasActivity ? (
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 3, px: 2.75, py: 2, flexShrink: 0, borderBottom: 1, borderColor: 'divider' }}>
              <Stat label="Cost" isAccent>
                {formatCalendarUsd(day.costUsd)}
              </Stat>
              <Stat label="Tokens">{formatCompact(day.tokens)}</Stat>
              <Stat label="Skill runs">{day.skillCalls}</Stat>
              <Stat label="Agent runs">{day.subagentCalls}</Stat>
              <Stat label="Sessions">{day.sessions}</Stat>
              <Stat label="Active time">{formatActiveTime(day.activeSeconds)}</Stat>
            </Box>
          ) : null}
          <Box sx={{ flex: 1, minHeight: 0, overflowY: 'auto', px: 2.75, py: 2.25 }}>
            {day && hasActivity ? (
              <>
                <Box sx={{ ...SECTION_TITLE_SX, mb: 1.75 }}>All metrics — totals for the day</Box>
                <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1.25 }}>
                  <MetricTile label="Lines of code">
                    {`+${day.linesAdded.toLocaleString()}`}
                    <NegativeFigure>{`-${day.linesRemoved.toLocaleString()}`}</NegativeFigure>
                  </MetricTile>
                  <MetricTile label="Commits">{day.commits.toLocaleString()}</MetricTile>
                  <MetricTile label="Pull requests">{day.pullRequests.toLocaleString()}</MetricTile>
                  <MetricTile label="Tool decisions">
                    {`${day.decisionsAccepted.toLocaleString()} accepted`}
                    <NegativeFigure>{`${day.decisionsRejected.toLocaleString()} rejected`}</NegativeFigure>
                  </MetricTile>
                </Box>

                <Box sx={{ ...SECTION_TITLE_SX, mt: 3, mb: 1.25 }}>Cost & tokens by model</Box>
                {modelRows.length > 0 ? (
                  <BreakdownList rows={modelRows} layout="stacked" showColorDot />
                ) : (
                  <Box sx={MUTED_TEXT_SX}>{isDetailLoading ? 'Loading…' : 'No model usage recorded for this day.'}</Box>
                )}

                <Box sx={{ ...SECTION_TITLE_SX, mt: 3, mb: 0.5 }}>Skills invoked</Box>
                <InvocationList rows={skillUsage} isLoading={isDetailLoading} emptyText="No skills were invoked this day." />

                <Box sx={{ ...SECTION_TITLE_SX, mt: 3, mb: 0.5 }}>Subagents invoked</Box>
                <InvocationList
                  rows={subagentUsage}
                  isLoading={isDetailLoading}
                  emptyText="No subagents were dispatched this day."
                />
              </>
            ) : (
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.75, py: 3, fontSize: 13, lineHeight: 1.5, color: 'text.secondary' }}>
                <CalendarMonthOutlinedIcon sx={{ fontSize: 28, color: 'text.disabled', flexShrink: 0 }} />
                <span>No usage recorded for this day — no sessions ran and no metrics were emitted.</span>
              </Box>
            )}
          </Box>
          <Box sx={{ px: 2.75, py: 2, flexShrink: 0, borderTop: 1, borderColor: 'divider' }}>
            <Box
              component="button"
              type="button"
              onClick={onOpenInMetrics}
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 1.1,
                width: '100%',
                height: 46,
                border: 'none',
                borderRadius: radii.sm,
                background: gradients.auroraAction,
                color: neutralColors.white,
                fontFamily: fontFamilies.display,
                fontSize: 14,
                fontWeight: 700,
                letterSpacing: '.3px',
                cursor: 'pointer',
                boxShadow: (t) => `0 8px 22px ${alpha(t.palette.primary.main, 0.32)}`,
                '&:hover': { filter: 'brightness(1.06)' },
                '&:focus-visible': { outline: (t) => `2px solid ${t.palette.primary.main}`, outlineOffset: 2 },
              }}
            >
              Open in Metrics Explorer
              <ArrowForwardIcon sx={{ fontSize: 18 }} />
            </Box>
          </Box>
        </>
      ) : null}
    </PeekDrawer>
  );
};

export default UsageDayDrawer;
