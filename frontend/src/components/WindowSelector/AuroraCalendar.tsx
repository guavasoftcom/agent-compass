import { useMemo, useState } from 'react';
import { Box, IconButton, Stack, Typography } from '@mui/material';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';

/**
 * Self-contained Aurora range calendar — no @mui/x-date-pickers dependency and no
 * native browser popup, so it can be fully themed in light and dark.
 *
 * Works against the existing datetime-local string contract used by WindowSelector:
 * values look like "YYYY-MM-DDTHH:mm". The calendar edits the date portion of each
 * bound and preserves the time-of-day (defaulting to 00:00 for start, 23:59 for end).
 * A pair of native <input type="time"> fields (theme-styled) handles the time.
 */

const DOW = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];
const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

const pad = (n: number): string => String(n).padStart(2, '0');

const toLocal = (d: Date): string =>
  `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`;

const parse = (value: string): Date | null => {
  if (!value) {
    return null;
  }
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
};

const timePart = (value: string): string => {
  const d = parse(value);
  return d ? `${pad(d.getHours())}:${pad(d.getMinutes())}` : '';
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

  const handleDayClick = (date: Date) => {
    const key = dayKey(date);
    const startExists = start != null;
    const endExists = end != null;

    // Start a fresh range when nothing is set, or when a full range already exists.
    if (!startExists || (startExists && endExists)) {
      const time = timePart(startInput) || '00:00';
      const [h, m] = time.split(':').map(Number);
      const next = new Date(date.getFullYear(), date.getMonth(), date.getDate(), h, m);
      onStartInputChange(toLocal(next));
      onEndInputChange('');
      return;
    }

    // Start set, no end yet.
    if (startKey != null && key < startKey) {
      // Clicked before the start → move the start.
      const time = timePart(startInput) || '00:00';
      const [h, m] = time.split(':').map(Number);
      onStartInputChange(
        toLocal(new Date(date.getFullYear(), date.getMonth(), date.getDate(), h, m)),
      );
      return;
    }
    const time = timePart(endInput) || '23:59';
    const [h, m] = time.split(':').map(Number);
    onEndInputChange(
      toLocal(new Date(date.getFullYear(), date.getMonth(), date.getDate(), h, m)),
    );
  };

  const handleTimeChange = (which: 'start' | 'end', time: string) => {
    if (!time) {
      return;
    }
    const [h, m] = time.split(':').map(Number);
    if (which === 'start' && start) {
      const next = new Date(start.getFullYear(), start.getMonth(), start.getDate(), h, m);
      onStartInputChange(toLocal(next));
    } else if (which === 'end' && end) {
      const next = new Date(end.getFullYear(), end.getMonth(), end.getDate(), h, m);
      onEndInputChange(toLocal(next));
    }
  };

  return (
    <Box sx={{ width: 300 }}>
      <Stack
        direction="row"
        sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1 }}
      >
        <Typography sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 700, fontSize: 15 }}>
          {MONTHS[view.month]} {view.year}
        </Typography>
        <Stack direction="row" spacing={0.5}>
          <IconButton
            size="small"
            onClick={() => moveMonth(-1)}
            aria-label="previous month"
            sx={(t) => ({
              border: `1px solid ${t.palette.divider}`,
              borderRadius: 1.25,
              '&:hover': { color: 'primary.main', borderColor: 'primary.main' },
            })}
          >
            <ChevronLeftIcon fontSize="small" />
          </IconButton>
          <IconButton
            size="small"
            onClick={() => moveMonth(1)}
            aria-label="next month"
            sx={(t) => ({
              border: `1px solid ${t.palette.divider}`,
              borderRadius: 1.25,
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

          return (
            <Box
              key={key}
              role="button"
              onClick={() => handleDayClick(cell.date as Date)}
              sx={(t) => ({
                aspectRatio: '1 / 1',
                display: 'grid',
                placeItems: 'center',
                fontSize: 13,
                fontWeight: isEdge ? 700 : 500,
                borderRadius: inRange && !isEdge ? 0 : 1.25,
                cursor: 'pointer',
                color: isEdge ? '#fff' : 'text.primary',
                position: 'relative',
                ...(inRange &&
                  !isEdge && {
                    bgcolor:
                      t.palette.mode === 'dark'
                        ? 'rgba(139,92,255,0.16)'
                        : 'rgba(124,77,255,0.10)',
                    ...(key === startKey && { borderRadius: '10px 0 0 10px' }),
                    ...(key === endKey && { borderRadius: '0 10px 10px 0' }),
                  }),
                ...(isToday &&
                  !isEdge && {
                    boxShadow: `inset 0 0 0 1.5px ${
                      t.palette.mode === 'dark'
                        ? 'rgba(139,92,255,0.4)'
                        : 'rgba(124,77,255,0.32)'
                    }`,
                  }),
                ...(isEdge && {
                  background: 'linear-gradient(135deg, #8b5cff, #ff6ad5)',
                  boxShadow: '0 5px 14px rgba(124,77,255,0.45)',
                }),
                '&:hover': isEdge
                  ? undefined
                  : { bgcolor: t.palette.action.hover },
              })}
            >
              {cell.label}
            </Box>
          );
        })}
      </Box>

      <Stack direction="row" spacing={1.25} sx={{ mt: 1.75 }}>
        <Box sx={{ flex: 1 }}>
          <Typography
            sx={{ fontSize: 11, fontWeight: 600, color: 'text.secondary', mb: 0.5 }}
          >
            Start time
          </Typography>
          <Box
            component="input"
            type="time"
            value={timePart(startInput)}
            onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
              handleTimeChange('start', event.target.value)
            }
            disabled={!start}
            sx={(t) => ({
              width: '100%',
              height: 38,
              px: 1.25,
              borderRadius: 1.25,
              border: `1px solid ${t.palette.divider}`,
              bgcolor:
                t.palette.mode === 'dark'
                  ? 'rgba(255,255,255,0.04)'
                  : 'rgba(28,24,48,0.04)',
              color: 'text.primary',
              colorScheme: t.palette.mode,
              fontFamily: 'inherit',
              fontSize: 13,
              '&:focus': { outline: 'none', borderColor: 'primary.main' },
            })}
          />
        </Box>
        <Box sx={{ flex: 1 }}>
          <Typography
            sx={{ fontSize: 11, fontWeight: 600, color: 'text.secondary', mb: 0.5 }}
          >
            End time
          </Typography>
          <Box
            component="input"
            type="time"
            value={timePart(endInput)}
            onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
              handleTimeChange('end', event.target.value)
            }
            disabled={!end}
            sx={(t) => ({
              width: '100%',
              height: 38,
              px: 1.25,
              borderRadius: 1.25,
              border: `1px solid ${t.palette.divider}`,
              bgcolor:
                t.palette.mode === 'dark'
                  ? 'rgba(255,255,255,0.04)'
                  : 'rgba(28,24,48,0.04)',
              color: 'text.primary',
              colorScheme: t.palette.mode,
              fontFamily: 'inherit',
              fontSize: 13,
              '&:focus': { outline: 'none', borderColor: 'primary.main' },
            })}
          />
        </Box>
      </Stack>
    </Box>
  );
};

export default AuroraCalendar;
