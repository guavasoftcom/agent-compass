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
import { formatCompact, shortModelName } from '../../../../lib/format';
import { colorForIndex, radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import type { UsageCalendarDay } from '../../usageCalendarApi';
import {
  buildModelMix,
  formatActiveTime,
  formatCalendarUsd,
  formatLongDate,
  formatShortMonth,
  formatShortWeekday,
  isActiveDay,
  toDateKey,
} from '../../usageCalendarDerivations';

export type CalendarDayCellVariant = 'month' | 'week';

export interface CalendarDayCellProps {
  /**
   * `'month'` is the compact grid cell; `'week'` is the taller cell with room for sessions and active
   * time, a spend-by-model bar, and commit / pull-request badges.
   */
  variant: CalendarDayCellVariant;
  date: Date;
  /** Whether the date belongs to the visible period (a month grid also shows the neighbours' days). */
  isInPeriod: boolean;
  isFuture: boolean;
  isToday: boolean;
  /** The day's row, or undefined while loading / when the range returned none. */
  day: UsageCalendarDay | undefined;
  /** Heat tint share (0-100) of `heatColor` mixed into the surface; undefined = plain surface. */
  heatPercent: number | undefined;
  heatColor: string;
  /** True while the rollup loads or after it failed: there is no answer to show, so the cell is blank and inert. */
  isDayDataUnavailable: boolean;
  isSelected: boolean;
  onSelect: (dateKey: string) => void;
}

// Index into the shared chart palette for each figure's dot; the KPI cards use the same indices, so a
// metric wears one color across the whole page.
const COST_COLOR_INDEX = 0;
const TOKENS_COLOR_INDEX = 1;
const SKILLS_COLOR_INDEX = 2;

const OUT_OF_PERIOD_OPACITY = 0.28;
const FUTURE_OPACITY = 0.42;
const MONTH_CELL_MIN_HEIGHT = 114;
const WEEK_CELL_MIN_HEIGHT = 264;
// Room between the week cell's date and the figures below it (the mockup's 22px).
const WEEK_STATS_TOP_GAP = '22px';
const MIX_BAR_HEIGHT = 6;

const TodayTag = () => (
  <Box
    component="span"
    sx={{
      typography: 'eyebrowSm',
      fontSize: 8.5,
      fontWeight: 800,
      color: 'primary.main',
      bgcolor: (t) => alpha(t.palette.primary.main, 0.14),
      px: 0.75,
      py: '2px',
      borderRadius: '5px',
    }}
  >
    Today
  </Box>
);

const FigureRow = ({
  colorIndex,
  value,
  unit,
}: {
  colorIndex: number;
  value: ReactNode;
  unit?: string;
}) => (
  <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 0.75, fontSize: 12 }}>
    <Box
      component="i"
      sx={{
        width: 6,
        height: 6,
        borderRadius: '2px',
        flexShrink: 0,
        alignSelf: 'center',
        bgcolor: colorForIndex(colorIndex),
      }}
    />
    <Box component="span" sx={{ fontWeight: 700, color: 'text.primary', fontVariantNumeric: 'tabular-nums' }}>
      {value}
    </Box>
    {unit ? (
      <Box component="span" sx={{ color: 'text.disabled', fontSize: 10 }}>
        {unit}
      </Box>
    ) : null}
  </Box>
);

const pluralize = (count: number, singular: string, plural = `${singular}s`): string =>
  `${count} ${count === 1 ? singular : plural}`;

const MetaFigure = ({ children }: { children: ReactNode }) => (
  <Box component="b" sx={{ color: 'text.primary', fontWeight: 700 }}>
    {children}
  </Box>
);

/** Sessions and active time on one line, e.g. "4 sessions · 2h 11m active". */
const SessionsMeta = ({ day }: { day: UsageCalendarDay }) => (
  <Box sx={{ mt: '9px', fontSize: 11.5, color: 'text.secondary' }}>
    <MetaFigure>{day.sessions}</MetaFigure> {day.sessions === 1 ? 'session' : 'sessions'} &middot;{' '}
    <MetaFigure>{formatActiveTime(day.activeSeconds)}</MetaFigure> active
  </Box>
);

/**
 * A thin bar splitting the day's spend by model, each segment in the model's palette color. Drawn only
 * when the day has model-attributed spend; a day without any renders nothing rather than an empty track.
 */
const SpendByModelBar = ({ day }: { day: UsageCalendarDay }) => {
  const segments = buildModelMix(day);
  if (segments.length === 0) {
    return null;
  }
  const description = segments
    .map((segment) => `${shortModelName(segment.model)} ${Math.round(segment.percent)}%`)
    .join(', ');
  return (
    <Box sx={{ mt: '10px' }}>
      <Box
        role="img"
        aria-label={`Spend by model: ${description}`}
        sx={{
          height: MIX_BAR_HEIGHT,
          borderRadius: '4px',
          overflow: 'hidden',
          display: 'flex',
          bgcolor: 'action.hover',
        }}
      >
        {segments.map((segment) => (
          <Box
            key={segment.model}
            component="span"
            title={`${shortModelName(segment.model)} · ${Math.round(segment.percent)}%`}
            sx={{ height: '100%', width: `${segment.percent}%`, bgcolor: colorForIndex(segment.colorIndex) }}
          />
        ))}
      </Box>
      <Box sx={{ typography: 'eyebrowSm', fontSize: 9.5, color: 'text.disabled', mt: '5px' }}>Spend by model</Box>
    </Box>
  );
};

/** Commit and pull-request pills, each only when non-zero; nothing at all when both are. */
const OutputBadges = ({ day }: { day: UsageCalendarDay }) => {
  const badges = [
    day.commits > 0 ? pluralize(day.commits, 'commit') : null,
    day.pullRequests > 0 ? pluralize(day.pullRequests, 'PR') : null,
  ].filter((badge): badge is string => badge != null);
  if (badges.length === 0) {
    return null;
  }
  return (
    <Box sx={{ display: 'flex', gap: '6px', mt: '9px', flexWrap: 'wrap' }}>
      {badges.map((badge) => (
        <Box
          key={badge}
          component="span"
          sx={{
            fontSize: 10.5,
            fontWeight: 600,
            color: 'text.secondary',
            bgcolor: 'action.hover',
            px: 1,
            py: '3px',
            borderRadius: '6px',
          }}
        >
          {badge}
        </Box>
      ))}
    </Box>
  );
};

const ariaLabelFor = (date: Date, day: UsageCalendarDay | undefined): string => {
  const title = formatLongDate(date);
  if (day == null || !isActiveDay(day)) {
    return `${title}: no activity`;
  }
  return (
    `${title}: ${formatCalendarUsd(day.costUsd)} cost, ${formatCompact(day.tokens)} tokens, ` +
    `${day.skillCalls} skill runs`
  );
};

const CalendarDayCell = ({
  variant,
  date,
  isInPeriod,
  isFuture,
  isToday,
  day,
  heatPercent,
  heatColor,
  isDayDataUnavailable,
  isSelected,
  onSelect,
}: CalendarDayCellProps) => {
  const dateKey = toDateKey(date);
  const isMonth = variant === 'month';
  // Inert while loading or failed: a click then would open the drawer on a day whose figures have not
  // arrived, and a missing row must not be read as "No activity".
  const isClickable = isInPeriod && !isFuture && !isDayDataUnavailable;
  const hasActivity = isActiveDay(day);

  const renderBody = (): ReactNode => {
    if (!isInPeriod || isDayDataUnavailable) {
      return null;
    }
    // A month cell sinks its figures to the bottom; a week cell reads top-down from the date.
    const bodyTopSpacing = isMonth ? 'auto' : WEEK_STATS_TOP_GAP;
    if (isFuture) {
      return (
        <Box sx={{ mt: isMonth ? 'auto' : '14px', fontSize: isMonth ? 11 : 11.5, color: 'text.disabled' }}>{'—'}</Box>
      );
    }
    if (day == null || !hasActivity) {
      return (
        <Box sx={{ mt: isMonth ? 'auto' : '14px', fontSize: isMonth ? 11 : 11.5, color: 'text.disabled' }}>
          No activity
        </Box>
      );
    }
    return (
      <>
        <Box sx={{ mt: bodyTopSpacing, display: 'flex', flexDirection: 'column', gap: isMonth ? '5px' : '7px' }}>
          <FigureRow colorIndex={COST_COLOR_INDEX} value={formatCalendarUsd(day.costUsd)} />
          <FigureRow colorIndex={TOKENS_COLOR_INDEX} value={formatCompact(day.tokens)} unit="tok" />
          <FigureRow colorIndex={SKILLS_COLOR_INDEX} value={day.skillCalls} unit="skill runs" />
        </Box>
        {isMonth ? null : (
          <>
            <SessionsMeta day={day} />
            <SpendByModelBar day={day} />
            <OutputBadges day={day} />
          </>
        )}
      </>
    );
  };

  return (
    <Box
      component={isClickable ? 'button' : 'div'}
      type={isClickable ? 'button' : undefined}
      onClick={isClickable ? () => onSelect(dateKey) : undefined}
      aria-label={isClickable ? ariaLabelFor(date, day) : undefined}
      aria-pressed={isClickable ? isSelected : undefined}
      data-date={dateKey}
      sx={(theme) => {
        let backgroundColor = theme.palette.background.paper;
        if (!isInPeriod) {
          backgroundColor = 'transparent';
        } else if (!isFuture && heatPercent != null) {
          backgroundColor = `color-mix(in srgb, ${heatColor} ${heatPercent.toFixed(0)}%, ${theme.palette.background.paper})`;
        }
        return {
          // Button reset: a clickable cell is a real <button>, so give it none of the UA chrome.
          font: 'inherit',
          color: 'inherit',
          textAlign: 'left',
          width: '100%',
          minWidth: 0,
          position: 'relative',
          display: 'flex',
          flexDirection: 'column',
          border: 1,
          borderColor: 'divider',
          borderRadius: isMonth ? radii.sm : radii.lg,
          p: isMonth ? '10px 12px' : '14px 16px',
          minHeight: isMonth ? MONTH_CELL_MIN_HEIGHT : WEEK_CELL_MIN_HEIGHT,
          backgroundColor,
          opacity: !isInPeriod ? OUT_OF_PERIOD_OPACITY : isFuture ? FUTURE_OPACITY : 1,
          cursor: isClickable ? 'pointer' : 'default',
          transition: 'box-shadow .15s, transform .15s',
          boxShadow: isToday || isSelected ? `inset 0 0 0 2px ${theme.palette.primary.main}` : 'none',
          '&:hover': isClickable
            ? {
                boxShadow: `0 10px 26px ${alpha(theme.palette.text.primary, 0.14)}${
                  isToday || isSelected ? `, inset 0 0 0 2px ${theme.palette.primary.main}` : ''
                }`,
                transform: 'translateY(-1px)',
              }
            : undefined,
          '&:focus-visible': { outline: `2px solid ${theme.palette.primary.main}`, outlineOffset: 2 },
        };
      }}
    >
      {isMonth ? (
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1.1 }}>
          <Box
            component="span"
            sx={{ fontFamily: fontFamilies.display, fontWeight: 700, fontSize: 13, color: 'text.primary' }}
          >
            {date.getDate()}
          </Box>
          {isToday ? <TodayTag /> : null}
        </Box>
      ) : (
        <>
          <Box sx={{ typography: 'eyebrowSm', color: 'text.disabled' }}>{formatShortWeekday(date)}</Box>
          <Box
            sx={{
              display: 'flex',
              alignItems: 'baseline',
              gap: 0.75,
              mt: '2px',
              fontFamily: fontFamilies.display,
              fontSize: 17,
              fontWeight: 800,
              letterSpacing: '-0.4px',
              color: 'text.primary',
            }}
          >
            {isToday ? <TodayTag /> : null}
            {date.getDate()}
            <Box component="span" sx={{ fontSize: 11, fontWeight: 600, color: 'text.disabled' }}>
              {formatShortMonth(date)}
            </Box>
          </Box>
        </>
      )}
      {renderBody()}
    </Box>
  );
};

export default CalendarDayCell;
