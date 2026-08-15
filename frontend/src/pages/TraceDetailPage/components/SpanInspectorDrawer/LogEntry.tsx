import { useState } from 'react';
import { alpha, Box, useTheme } from '@mui/material';
import type { LogRow } from '../../../../api';
import { formatDuration } from '../../../TracesPage/tracesApi';
import { severityColor, severityLabel } from '../../severity';
import { clock } from './drawerParts';
import { LongAttrValue, LONG_VALUE_LOG } from './longValue';
import { fontFamilies } from '../../../../theme/typography';

// Keys already surfaced in the row header — don't repeat them in the expanded
// attribute list.
const LOG_HEADER_KEYS = new Set([
  'event.name',
  'event.timestamp',
  'tool',
  'session.id',
]);

// Per-row presentation for a log inside the span dock. Click to expand the
// full attribute payload — long values clamp and open in the drawer's shared
// "view formatted" modal (see longValue.tsx), which runs the raw text through
// `tryParseJson` (jsonrepair under the hood) so the 60 KB-truncated payloads
// OTLP ships still display as formatted JSON. The clamp, the button, and the
// modal used to live here; they moved out unchanged so the Events and
// Attributes grids get the same treatment.
const LogEntry = ({ log, spanStartMs }: { log: LogRow; spanStartMs: number }) => {
  const theme = useTheme();
  const [expanded, setExpanded] = useState(false);
  const sev = severityLabel(log.severityNumber);
  const sevColor = severityColor(log.severityNumber);
  const sevPalette = sevColor === 'error' ? theme.palette.error.main : sevColor === 'warning' ? theme.palette.warning.main : sevColor === 'info' ? theme.palette.primary.main : theme.palette.text.disabled;
  const logMs = Date.parse(log.timestamp);
  const offsetNanos = (logMs - spanStartMs) * 1e6;
  const eventName = typeof log.attributes?.['event.name'] === 'string' ? (log.attributes['event.name'] as string) : null;
  const tool = typeof log.attributes?.['tool'] === 'string' ? (log.attributes['tool'] as string) : null;
  const detailEntries = Object.entries(log.attributes ?? {}).filter(([k]) => !LOG_HEADER_KEYS.has(k));
  const hasDetail = detailEntries.length > 0 || Boolean(log.scopeName);
  return (
    <Box
      onClick={hasDetail ? () => setExpanded((v) => !v) : undefined}
      sx={{ borderBottom: 1, borderColor: 'divider', cursor: hasDetail ? 'pointer' : 'default', '&:hover': hasDetail ? { bgcolor: 'action.hover' } : undefined }}
    >
      <Box sx={{ display: 'flex', gap: 1, py: 1, alignItems: 'baseline', flexWrap: 'wrap' }}>
        {hasDetail ? (
          <Box
            component="svg"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth={2.4}
            sx={{
              width: 11,
              height: 11,
              color: 'text.disabled',
              flexShrink: 0,
              alignSelf: 'center',
              transition: 'transform 0.14s ease',
              transform: expanded ? 'rotate(90deg)' : 'none',
            }}
          >
            <path d="M9 6l6 6-6 6" />
          </Box>
        ) : (
          // Same-width spacer so timestamps and severity badges stay aligned in
          // a column across expandable and non-expandable rows.
          <Box component="span" sx={{ width: 11, flexShrink: 0 }} />
        )}
        <Box component="span" sx={{ typography: 'mono', fontSize: 10.5, color: 'text.disabled', whiteSpace: 'nowrap' }} title={new Date(logMs).toISOString()}>
          T+{formatDuration(offsetNanos)} · {clock(logMs)}
        </Box>
        <Box component="span" sx={{ fontFamily: fontFamilies.display, fontSize: 9.5, fontWeight: 700, color: sevPalette, minWidth: 38 }}>{sev}</Box>
        {eventName ? <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', height: 16, px: 0.75, borderRadius: '4px', bgcolor: (t) => alpha(t.palette.primary.main, 0.14), color: 'primary.main', typography: 'mono', fontSize: 10, fontWeight: 600 }}>{eventName}</Box> : null}
        {tool ? <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', height: 16, px: 0.75, borderRadius: '4px', bgcolor: (t) => alpha(t.palette.info.main, 0.14), color: 'info.main', typography: 'mono', fontSize: 10, fontWeight: 600 }}>{tool}</Box> : null}
        {log.body ? <Box component="span" sx={{ fontSize: 12.5, color: 'text.primary', wordBreak: 'break-word' }}>{log.body}</Box> : null}
      </Box>
      {expanded ? (
        <Box sx={{ pb: 1, pl: 1, display: 'flex', flexDirection: 'column', gap: 0.4 }} onClick={(e) => e.stopPropagation()}>
          {log.scopeName ? (
            <Box sx={{ display: 'grid', gridTemplateColumns: 'minmax(140px,auto) 1fr', gap: 1.5, typography: 'mono', fontSize: 11.5 }}>
              <Box component="span" sx={{ color: 'text.secondary' }}>scope</Box>
              <Box component="span" sx={{ color: 'text.primary', wordBreak: 'break-word' }}>{log.scopeName}</Box>
            </Box>
          ) : null}
          {detailEntries.map(([k, v]) => (
            <Box key={k} sx={{ display: 'grid', gridTemplateColumns: 'minmax(140px,auto) 1fr', gap: 1.5, typography: 'mono', fontSize: 11.5 }}>
              <Box component="span" sx={{ color: 'text.secondary' }}>{k}</Box>
              <LongAttrValue
                attrKey={k}
                value={v}
                limit={LONG_VALUE_LOG}
                color={typeof v === 'number' ? 'info.main' : typeof v === 'string' ? 'success.main' : 'text.primary'}
              />            </Box>
          ))}
        </Box>
      ) : null}
    </Box>
  );
};

export default LogEntry;
