import { useRef } from 'react';
import type { ReactNode } from 'react';
import { Box, Typography, alpha, useTheme } from '@mui/material';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import type { LogRow } from '../../../../api';
import { eventNameOf, severityOf, toolNameOf } from '../../logsApi';
import { severityColor } from '../severity';
import { fontFamilies } from '../../../../theme/typography';
import { SeverityChip } from '../SeverityChip';
import LogRowDetail from './LogRowDetail';

// LogStream — cursor-paged, infinite-scroll log list (the Logs page Stream view).
// This file owns the scroll container, empty/loading states, and the collapsed
// row; the expanded panel and the severity badge are extracted.
//
//   ┌─ scroll body (onScroll → onLoadMore near bottom) ───────────────────┐
//   │ ▏ 12s ago  [INFO]  scope  log body…            [event][tool]    ›   │ ← collapsed row
//   │   └─▶ <LogRowDetail/>  (body + meta + Open-in-trace/Copy + attrs)   │ ← when expanded
//   │ ▏ 14s ago  [WARN]  …                                            ›   │
//   │ ░░ skeleton rows … (while loading)                                  │
//   └─────────────────────────────────────────────────────────────────────┘
//   ┌─ footer ────────────────────────────────────────────────────────────┐
//   │ ⟳ Loading more… │ Loaded N of M · scroll for more │ All M loaded     │
//   └─────────────────────────────────────────────────────────────────────┘
//
// Pieces: SeverityChip (../SeverityChip, shared with LogTable), LogRowDetail
// (the expanded panel), and local Tag / Skeleton / Spinner. Click a row →
// onToggleExpand(id); the parent owns the `expanded` set and the data/paging.

const Tag = ({ children, strong }: { children: ReactNode; strong?: boolean }) => (
  <Box
    component="span"
    sx={{
      display: 'inline-flex',
      alignItems: 'center',
      height: 20,
      px: 1,
      borderRadius: 0.75,
      bgcolor: 'action.hover',
      color: strong ? 'text.primary' : 'text.secondary',
      fontFamily: fontFamilies.mono,
      fontSize: 11,
      fontWeight: 600,
      whiteSpace: 'nowrap',
    }}
  >
    {children}
  </Box>
);

const relTime = (iso: string): string => {
  const d = (Date.now() - Date.parse(iso)) / 1000;
  if (d < 60) {
    return `${Math.max(1, Math.round(d))}s ago`;
  }
  if (d < 3600) {
    return `${Math.round(d / 60)}m ago`;
  }
  if (d < 86400) {
    return `${Math.round(d / 3600)}h ago`;
  }
  return `${Math.round(d / 86400)}d ago`;
};

interface Props {
  rows: LogRow[];
  total: number;
  loading: boolean;
  hasMore: boolean;
  expanded: Set<number>;
  onToggleExpand: (id: number) => void;
  onLoadMore: () => void;
}

const LogStream = ({ rows, total, loading, hasMore, expanded, onToggleExpand, onLoadMore }: Props) => {
  const theme = useTheme();
  const scrollRef = useRef<HTMLDivElement | null>(null);

  const handleScroll = () => {
    const el = scrollRef.current;
    if (!el) {
      return;
    }
    if (el.scrollTop + el.clientHeight > el.scrollHeight - 320) {
      onLoadMore();
    }
  };

  const empty = !rows.length && !loading;

  return (
    <>
      <Box ref={scrollRef} onScroll={handleScroll} sx={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
        {empty ? (
          <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', gap: 1.5, color: 'text.secondary', textAlign: 'center', p: 5 }}>
            <Typography sx={{ fontFamily: fontFamilies.display, color: 'text.primary', fontSize: 16, fontWeight: 700 }}>
              No logs match these filters
            </Typography>
            <Typography sx={{ fontSize: 13.5 }}>Loosen a facet, clear the search, or widen the time window.</Typography>
          </Box>
        ) : null}

        {rows.map((row) => {
          const sev = severityOf(row);
          const open = expanded.has(row.id);
          const event = eventNameOf(row);
          const tool = toolNameOf(row);
          return (
            <Box key={row.id} sx={{ contentVisibility: 'auto', containIntrinsicSize: 'auto 38px' }}>
              <Box
                onClick={() => onToggleExpand(row.id)}
                sx={{
                  display: 'grid',
                  gridTemplateColumns: '4px 120px 70px 1fr auto 22px',
                  alignItems: 'center',
                  gap: 1.5,
                  pr: 2,
                  borderBottom: 1,
                  borderColor: 'divider',
                  cursor: 'pointer',
                  minHeight: 38,
                  bgcolor: open ? 'action.hover' : 'transparent',
                  transition: 'background-color .1s',
                  '&:hover': { bgcolor: 'action.hover' },
                }}
              >
                <Box sx={{ alignSelf: 'stretch', bgcolor: severityColor(theme, sev) }} />
                <Box component="span" sx={{ fontFamily: fontFamilies.mono, fontSize: 12, color: 'text.secondary', fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }} title={new Date(row.timestamp).toLocaleString()}>
                  {relTime(row.timestamp)}
                </Box>
                <SeverityChip severity={sev} />
                <Box sx={{ fontFamily: fontFamilies.mono, fontSize: 12.5, color: 'text.primary', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 }}>
                  <Box component="span" sx={{ color: 'text.disabled', mr: 1.1 }}>
                    {row.scopeName}
                  </Box>
                  {row.body}
                </Box>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, whiteSpace: 'nowrap' }}>
                  {event ? <Tag>{event}</Tag> : null}
                  {tool ? <Tag strong>{tool}</Tag> : null}
                </Box>
                <Box sx={{ display: 'grid', placeItems: 'center', color: open ? 'primary.main' : 'text.disabled', transform: open ? 'rotate(90deg)' : 'none', transition: 'transform .15s' }}>
                  <ChevronRightIcon sx={{ fontSize: 16 }} />
                </Box>
              </Box>
              {open ? <LogRowDetail row={row} /> : null}
            </Box>
          );
        })}

        {loading
          ? Array.from({ length: 8 }).map((_, i) => (
              <Box
                key={`sk-${i}`}
                sx={{ display: 'grid', gridTemplateColumns: '4px 120px 70px 1fr auto 22px', alignItems: 'center', gap: 1.5, pr: 2, borderBottom: 1, borderColor: 'divider', minHeight: 38 }}
              >
                <Box sx={{ alignSelf: 'stretch', bgcolor: 'action.hover' }} />
                {[70, 42, 280, 90].map((w, j) => (
                  <Skeleton key={j} width={w} />
                ))}
                <Box />
              </Box>
            ))
          : null}
      </Box>

      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 1.1,
          px: 2,
          py: 1.6,
          flexShrink: 0,
          borderTop: 1,
          borderColor: 'divider',
          fontSize: 12.5,
          color: 'text.secondary',
        }}
      >
        {loading ? (
          <>
            <Spinner />
            Loading more…
          </>
        ) : hasMore ? (
          <>
            Loaded{' '}
            <Box component="b" sx={{ color: 'text.primary', fontFamily: fontFamilies.display }}>
              {rows.length}
            </Box>{' '}
            of{' '}
            <Box component="b" sx={{ color: 'text.primary', fontFamily: fontFamilies.display }}>
              {total.toLocaleString()}
            </Box>
            <Box component="span" sx={{ color: 'text.disabled' }}>· scroll for more · cursor-paged</Box>
          </>
        ) : rows.length ? (
          <Box component="span" sx={{ color: 'text.disabled' }}>
            All{' '}
            <Box component="b" sx={{ color: 'text.primary' }}>
              {total.toLocaleString()}
            </Box>{' '}
            matching events loaded
          </Box>
        ) : null}
      </Box>
    </>
  );
};

const Skeleton = ({ width }: { width: number }) => (
  <Box
    sx={{
      height: 11,
      width,
      maxWidth: '70%',
      borderRadius: 0.75,
      background: (t) =>
        `linear-gradient(90deg, ${t.palette.action.hover} 0%, ${alpha(t.palette.primary.main, 0.12)} 50%, ${t.palette.action.hover} 100%)`,
      backgroundSize: '200% 100%',
      animation: 'logShimmer 1.1s infinite',
      '@keyframes logShimmer': { '0%': { backgroundPosition: '200% 0' }, '100%': { backgroundPosition: '-200% 0' } },
    }}
  />
);

const Spinner = () => (
  <Box
    sx={{
      width: 14,
      height: 14,
      borderRadius: '50%',
      border: (t) => `2px solid ${t.palette.action.hover}`,
      borderTopColor: 'primary.main',
      animation: 'logSpin .7s linear infinite',
      '@keyframes logSpin': { to: { transform: 'rotate(360deg)' } },
    }}
  />
);

export default LogStream;
