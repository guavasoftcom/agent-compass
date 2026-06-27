import { useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Box, Typography, alpha, useTheme } from '@mui/material';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import CheckIcon from '@mui/icons-material/Check';
import TimelineIcon from '@mui/icons-material/Timeline';
import type { LogRow } from '../../../api';
import { AttributeList } from '../../../components/AttributeList';
import { eventNameOf, severityOf, toolNameOf, type Severity } from '../logsApi';
import { severityColor } from './LogHistogram';

export const SeverityChip = ({ severity }: { severity: Severity }) => {
  const theme = useTheme();
  const c = severityColor(theme, severity);
  const isDebug = severity === 'DEBUG';
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        height: 19,
        px: 1,
        borderRadius: 0.75,
        fontFamily: "'Sora', sans-serif",
        fontSize: 10,
        fontWeight: 700,
        letterSpacing: '0.5px',
        color: isDebug ? 'text.secondary' : c,
        bgcolor: isDebug ? 'action.hover' : alpha(c, 0.15),
      }}
    >
      {severity}
    </Box>
  );
};

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
      fontFamily: 'monospace',
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

const RowDetail = ({ row }: { row: LogRow }) => {
  const theme = useTheme();
  const [copied, setCopied] = useState(false);
  const attrs = row.attributes ?? {};
  const entries = Object.entries(attrs);
  const monoBg = alpha(theme.palette.text.primary, theme.palette.mode === 'dark' ? 0.04 : 0.035);
  // Copy the full in-memory LogRow to the clipboard — no backend round-trip.
  const handleCopyJson = () => {
    if (navigator.clipboard) {
      void navigator.clipboard.writeText(JSON.stringify(row, null, 2));
    }
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1200);
  };
  return (
    <Box sx={{ bgcolor: monoBg, px: 2, pt: 0.5, pb: 2.25, pl: 3 }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1.3fr 1fr' }, gap: 2.25, pt: 1.75 }}>
        <Box>
          <Typography sx={detailHeadSx}>Body</Typography>
          <Box
            sx={{
              fontFamily: 'monospace',
              fontSize: 12.5,
              lineHeight: 1.65,
              color: 'text.primary',
              bgcolor: 'background.paper',
              border: 1,
              borderColor: 'divider',
              borderRadius: 1.5,
              p: 1.75,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}
          >
            {row.body}
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2.25, flexWrap: 'wrap', mt: 1.75 }}>
            <MetaItem k="Timestamp" v={fullTimestamp(row.timestamp)} />
            {row.traceId ? <MetaItem k="Trace" v={`${row.traceId.slice(0, 16)}…`} /> : null}
            {row.spanId ? <MetaItem k="Span" v={row.spanId.slice(0, 12)} /> : null}
            {row.attributes?.['session.id'] ? <MetaItem k="Session" v={String(row.attributes['session.id'])} /> : null}
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mt: 1.5 }}>
            <Box
              component="a"
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.9,
                height: 30,
                px: 1.6,
                borderRadius: 1.1,
                bgcolor: (t) => alpha(t.palette.primary.main, 0.12),
                border: (t) => `1px solid ${alpha(t.palette.primary.main, 0.32)}`,
                color: 'primary.main',
                fontFamily: "'Sora', sans-serif",
                fontSize: 12.5,
                fontWeight: 600,
                cursor: 'pointer',
                textDecoration: 'none',
                '&:hover': { bgcolor: (t) => alpha(t.palette.primary.main, 0.2) },
              }}
            >
              <TimelineIcon sx={{ fontSize: 15 }} />
              Open in trace
            </Box>
            <Box
              component="span"
              role="button"
              onClick={handleCopyJson}
              sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, fontSize: 11.5, fontWeight: 600, color: copied ? 'success.main' : 'text.secondary', cursor: 'pointer', '&:hover': { color: copied ? 'success.main' : 'primary.main' } }}
            >
              {copied ? <CheckIcon sx={{ fontSize: 14 }} /> : <ContentCopyIcon sx={{ fontSize: 13 }} />}
              {copied ? 'Copied' : 'Copy JSON'}
            </Box>
          </Box>
        </Box>
        <Box>
          <Typography sx={detailHeadSx}>
            Attributes
            <Box component="span" sx={{ color: 'text.disabled', fontWeight: 500, ml: 0.75 }}>
              {entries.length}
            </Box>
          </Typography>
          <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1.5, overflow: 'hidden', bgcolor: 'background.paper', px: 1, py: 0.5 }}>
            {/* Shared AttributeList: JSON-stringifies object values, truncates long
                strings with a "View more" link, and opens the repair modal for
                truncated (~60kB) JSON payloads like api_response_body. */}
            <AttributeList attributes={attrs} disableBackground />
          </Box>
        </Box>
      </Box>
    </Box>
  );
};

const detailHeadSx = {
  fontFamily: "'Sora', sans-serif",
  fontSize: 10.5,
  fontWeight: 700,
  letterSpacing: '1px',
  textTransform: 'uppercase' as const,
  color: 'text.secondary',
  mb: 1.1,
};

// Full-precision timestamp for the expanded row (date + year + ms) — the collapsed
// row only shows relative time, with the absolute value in a hover title.
const fullTimestamp = (iso: string): string => {
  const d = new Date(iso);
  const base = d.toLocaleString('en-US', { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
  return `${base}.${String(d.getMilliseconds()).padStart(3, '0')}`;
};

const MetaItem = ({ k, v }: { k: string; v: string }) => (
  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
    <Box component="span" sx={{ fontFamily: "'Sora', sans-serif", fontSize: 10, fontWeight: 700, letterSpacing: '0.6px', textTransform: 'uppercase', color: 'text.disabled' }}>
      {k}
    </Box>
    <Box component="span" sx={{ fontFamily: 'monospace', fontSize: 12, color: 'text.primary' }}>
      {v}
    </Box>
  </Box>
);

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
            <Typography sx={{ fontFamily: "'Sora', sans-serif", color: 'text.primary', fontSize: 16, fontWeight: 700 }}>
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
                <Box component="span" sx={{ fontFamily: 'monospace', fontSize: 12, color: 'text.secondary', fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }} title={new Date(row.timestamp).toLocaleString()}>
                  {relTime(row.timestamp)}
                </Box>
                <SeverityChip severity={sev} />
                <Box sx={{ fontFamily: 'monospace', fontSize: 12.5, color: 'text.primary', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 }}>
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
              {open ? <RowDetail row={row} /> : null}
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
            <Box component="b" sx={{ color: 'text.primary', fontFamily: "'Sora', sans-serif" }}>
              {rows.length}
            </Box>{' '}
            of{' '}
            <Box component="b" sx={{ color: 'text.primary', fontFamily: "'Sora', sans-serif" }}>
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
