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
import { useMemo, useState } from 'react';
import { alpha, Box, IconButton, Stack, Typography } from '@mui/material';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import { auroraColors, gradients, neutralColors } from '../../theme/colors';
import { fontFamilies } from '../../theme/typography';
import { radii } from '../../theme/theme';

/**
 * Self-contained Aurora range calendar — no @mui/x-date-pickers dependency and no
 * native browser popup, so it can be fully themed in light and dark.
 *
 * Whole days only — no time-of-day input. `startInput`/`endInput` are plain
 * "YYYY-MM-DD" local-date strings; `WindowSelector` expands the picked days to
 * 00:00:00.000 (start) / 23:59:59.999 (end) of that calendar day when applying
 * the range.
 */

const DOW = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];
const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

const pad = (n: number): string => String(n).padStart(2, '0');

const toLocalDateString = (d: Date): string => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

const parse = (value: string): Date | null => {
  if (!value) {
    return null;
  }
  const [year, month, day] = value.split('-').map(Number);
  if (!year || !month || !day) {
    return null;
  }
  const date = new Date(year, month - 1, day);
  return Number.isNaN(date.getTime()) ? null : date;
};

// Strip time → midnight, for date-only comparisons.
const dayKey = (d: Date): number =>
  new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();

export interface AuroraCalendarProps {
  startInput: string;
  endInput: string;
  onStartInputChange: (next: string) => void;
  onEndInputChange: (next: string) => void;
}

const AuroraCalendar = ({
  startInput,
  endInput,
  onStartInputChange,
  onEndInputChange,
}: AuroraCalendarProps) => {
  const start = parse(startInput);
  const end = parse(endInput);

  const [view, setView] = useState(() => {
    const base = start ?? new Date();
    return { year: base.getFullYear(), month: base.getMonth() };
  });

  const today = new Date();
  const todayKey = dayKey(today);
  const startKey = start ? dayKey(start) : null;
  const endKey = end ? dayKey(end) : null;

  const cells = useMemo(() => {
    const first = new Date(view.year, view.month, 1);
    const startDow = first.getDay();
    const daysInMonth = new Date(view.year, view.month + 1, 0).getDate();
    const out: Array<{ date: Date | null; label: number }> = [];
    for (let i = 0; i < 42; i += 1) {
      const dayNum = i - startDow + 1;
      if (dayNum < 1 || dayNum > daysInMonth) {
        out.push({ date: null, label: 0 });
      } else {
        out.push({ date: new Date(view.year, view.month, dayNum), label: dayNum });
      }
    }
    return out;
  }, [view]);

  const moveMonth = (delta: number) => {
    setView((previous) => {
      const next = new Date(previous.year, previous.month + delta, 1);
      return { year: next.getFullYear(), month: next.getMonth() };
    });
  };

  const atCurrentMonth = view.year === today.getFullYear() && view.month === today.getMonth();

  const handleDayClick = (date: Date) => {
    const key = dayKey(date);
    if (key > todayKey) {
      return;
    }
    const startExists = start != null;
    const endExists = end != null;

    // Start a fresh range when nothing is set, or when a full range already exists.
    if (!startExists || (startExists && endExists)) {
      onStartInputChange(toLocalDateString(date));
      onEndInputChange('');
      return;
    }

    // Start set, no end yet.
    if (startKey != null && key < startKey) {
      // Clicked before the start → move the start.
      onStartInputChange(toLocalDateString(date));
      return;
    }
    onEndInputChange(toLocalDateString(date));
  };

  return (
    <Box sx={{ width: 300 }}>
      <Stack
        direction="row"
        sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1 }}
      >
        <Typography sx={{ fontFamily: fontFamilies.display, fontWeight: 700, fontSize: 15 }}>
          {MONTHS[view.month]} {view.year}
        </Typography>
        <Stack direction="row" spacing={0.5}>
          <IconButton
            size="small"
            onClick={() => moveMonth(-1)}
            aria-label="previous month"
            sx={(t) => ({
              border: `1px solid ${t.palette.divider}`,
              borderRadius: radii.sm,
              '&:hover': { color: 'primary.main', borderColor: 'primary.main' },
            })}
          >
            <ChevronLeftIcon fontSize="small" />
          </IconButton>
          <IconButton
            size="small"
            onClick={() => moveMonth(1)}
            disabled={atCurrentMonth}
            aria-label="next month"
            sx={(t) => ({
              border: `1px solid ${t.palette.divider}`,
              borderRadius: radii.sm,
              '&:hover': { color: 'primary.main', borderColor: 'primary.main' },
            })}
          >
            <ChevronRightIcon fontSize="small" />
          </IconButton>
        </Stack>
      </Stack>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'repeat(7, 1fr)',
          gap: '2px',
          mb: 0.5,
        }}
      >
        {DOW.map((d, index) => (
          <Typography
            key={`${d}-${index}`}
            sx={{
              textAlign: 'center',
              fontSize: 11,
              fontWeight: 600,
              color: 'text.disabled',
              py: 0.5,
            }}
          >
            {d}
          </Typography>
        ))}
      </Box>

      <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: '2px' }}>
        {cells.map((cell, index) => {
          if (!cell.date) {
            return <Box key={`empty-${index}`} />;
          }
          const key = dayKey(cell.date);
          const isStart = key === startKey;
          const isEnd = key === endKey;
          const inRange =
            startKey != null && endKey != null && key >= startKey && key <= endKey;
          const isToday = key === todayKey;
          const isEdge = isStart || isEnd;
          const isFuture = key > todayKey;

          return (
            <Box
              key={key}
              role="button"
              aria-disabled={isFuture}
              onClick={isFuture ? undefined : () => handleDayClick(cell.date as Date)}
              sx={(t) => ({
                aspectRatio: '1 / 1',
                display: 'grid',
                placeItems: 'center',
                fontSize: 13,
                fontWeight: isEdge ? 700 : 500,
                borderRadius: inRange && !isEdge ? 0 : radii.sm,
                cursor: isFuture ? 'not-allowed' : 'pointer',
                color: isEdge ? neutralColors.white : isFuture ? 'text.disabled' : 'text.primary',
                opacity: isFuture ? 0.38 : 1,
                position: 'relative',
                ...(inRange &&
                  !isEdge && {
                    bgcolor:
                      t.palette.mode === 'dark'
                        ? alpha(auroraColors.violetLight, 0.16)
                        : alpha(auroraColors.violet, 0.1),
                    ...(key === startKey && { borderRadius: '10px 0 0 10px' }),
                    ...(key === endKey && { borderRadius: '0 10px 10px 0' }),
                  }),
                ...(isToday &&
                  !isEdge && {
                    boxShadow: `inset 0 0 0 1.5px ${
                      t.palette.mode === 'dark'
                        ? alpha(auroraColors.violetLight, 0.4)
                        : alpha(auroraColors.violet, 0.32)
                    }`,
                  }),
                ...(isEdge && {
                  background: gradients.auroraAction,
                  boxShadow: `0 5px 14px ${alpha(auroraColors.violet, 0.45)}`,
                }),
                '&:hover': isEdge || isFuture
                  ? undefined
                  : { bgcolor: t.palette.action.hover },
              })}
            >
              {cell.label}
            </Box>
          );
        })}
      </Box>
    </Box>
  );
};

export default AuroraCalendar;
