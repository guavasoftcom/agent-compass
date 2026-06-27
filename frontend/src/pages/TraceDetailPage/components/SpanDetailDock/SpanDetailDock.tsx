import type { ReactNode } from 'react';
import { Box, Typography, useTheme } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import type { LogRow, SpanRow } from '../../../../api';
import { formatDuration } from '../../../TracesPage/tracesApi';
import { type TokenBreakdown } from '../../../TracesPage/tokenBreakdown';
import { clock, SectionTitle } from './dockParts';
import { useResizableHeight } from './useResizableHeight';
import LogEntry from './LogEntry';
import TokensSection from './TokensSection';
import SpanAttributesColumn from './SpanAttributesColumn';
import SpanEventsList from './SpanEventsList';

interface Props {
  span: SpanRow;
  selfNanos: number;
  tokens: TokenBreakdown;
  logs: LogRow[];
  onClose: () => void;
}

const SpanDetailDock = ({ span, selfNanos, tokens, logs, onClose }: Props) => {
  const theme = useTheme();
  const { height, onGripDown } = useResizableHeight(320);

  const startMs = Date.parse(span.startTimestamp);
  const endMs = Date.parse(span.endTimestamp);
  const durMs = span.durationNanos / 1e6;
  const selfMs = selfNanos / 1e6;
  const selfPct = durMs > 0 ? Math.round((selfMs / durMs) * 100) : 100;

  const meta: Array<[string, ReactNode]> = [
    ['span id', span.spanId],
    ['kind', span.kind ?? '—'],
    ['scope', span.scopeName ?? '—'],
    [
      'status',
      <Box key="st" component="span" sx={{ color: span.statusCode === 'error' ? 'error.main' : 'success.main' }}>
        {span.statusCode ?? 'ok'}
      </Box>,
    ],
    ['started', clock(startMs)],
    ['ended', clock(endMs)],
    ['duration', formatDuration(span.durationNanos)],
  ];

  return (
    <Box sx={{ height, flexShrink: 0, mt: 2, border: 1, borderColor: 'divider', borderRadius: 2.25, overflow: 'hidden', display: 'flex', flexDirection: 'column', bgcolor: 'background.paper' }}>
      <Box
        onMouseDown={onGripDown}
        title="Drag to resize"
        sx={{ height: 12, flexShrink: 0, cursor: 'row-resize', display: 'flex', alignItems: 'center', justifyContent: 'center', borderBottom: 1, borderColor: 'divider', '&::before': { content: '""', width: 40, height: 3, borderRadius: 1, bgcolor: 'divider' }, '&:hover::before': { bgcolor: 'primary.main' } }}
      />
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', px: 2, py: 1.25, borderBottom: 1, borderColor: 'divider' }}>
        <Typography sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: 14, fontWeight: 600 }}>{span.name}</Typography>
        <Box component="button" onClick={onClose} sx={{ display: 'grid', placeItems: 'center', width: 28, height: 28, border: 'none', borderRadius: 1, bgcolor: 'transparent', cursor: 'pointer', color: 'text.secondary', '&:hover': { bgcolor: 'action.hover', color: 'text.primary' } }}>
          <CloseIcon sx={{ fontSize: 16 }} />
        </Box>
      </Box>

      <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto', p: 2, display: 'flex', flexFlow: 'row wrap', gap: 3, alignItems: 'flex-start' }}>
        {/* col 1 — meta + self time + status message + tokens */}
        <Box sx={{ flex: 1, minWidth: 240, display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <Box sx={{ display: 'grid', gridTemplateColumns: 'auto 1fr', rowGap: 0.75, columnGap: 1.5 }}>
            {meta.map(([k, v]) => (
              <Box key={k} sx={{ display: 'contents' }}>
                <Box component="span" sx={{ fontFamily: "'Sora', sans-serif", fontSize: 9.5, fontWeight: 700, letterSpacing: '0.6px', textTransform: 'uppercase', color: 'text.disabled', alignSelf: 'center' }}>{k}</Box>
                <Box component="span" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: 12, color: 'text.primary' }}>{v}</Box>
              </Box>
            ))}
          </Box>
          {selfMs !== durMs ? (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 1.4, py: 1, border: 1, borderColor: 'divider', borderRadius: 1.25, fontSize: 12.5 }}>
              self time <b>{formatDuration(selfNanos)}</b>
              <Box sx={{ flex: 1, height: 6, borderRadius: 1, bgcolor: 'action.hover', overflow: 'hidden' }}>
                <Box sx={{ width: `${selfPct}%`, height: '100%', borderRadius: 1, background: `linear-gradient(90deg, ${theme.palette.warning.main}, ${theme.palette.warning.light})` }} />
              </Box>
              <b>{selfPct}%</b>
            </Box>
          ) : null}
          {span.statusMessage ? (
            <Box sx={{ px: 1.4, py: 1, border: 1, borderRadius: 1.25, fontSize: 12.5, color: 'error.main', borderColor: (t) => `color-mix(in srgb, ${t.palette.error.main} 36%, transparent)` }}>
              {span.statusMessage}
            </Box>
          ) : null}
          <TokensSection tokens={tokens} />
        </Box>

        {/* col 2 — tool group + attributes */}
        <SpanAttributesColumn attributes={span.attributes} />

        {/* col 3 — events + logs */}
        <Box sx={{ flex: 1, minWidth: 240, display: 'flex', flexDirection: 'column', gap: 1.75 }}>
          <SpanEventsList events={span.events} spanStartMs={startMs} />
          <Box>
            <SectionTitle count={logs.length}>Logs</SectionTitle>
            {logs.length ? (
              <Box sx={{ display: 'flex', flexDirection: 'column' }}>
                {logs.map((l) => (
                  <LogEntry key={l.id} log={l} spanStartMs={startMs} />
                ))}
              </Box>
            ) : (
              <Typography sx={{ fontSize: 12.5, color: 'text.disabled', fontStyle: 'italic' }}>No logs in this span.</Typography>
            )}
          </Box>
        </Box>
      </Box>
    </Box>
  );
};

export default SpanDetailDock;
