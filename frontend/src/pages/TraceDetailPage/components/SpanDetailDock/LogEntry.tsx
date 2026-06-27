import { useState } from 'react';
import { Alert, Box, Dialog, DialogContent, DialogTitle, IconButton, Tooltip, useTheme } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import type { LogRow } from '../../../../api';
import { tryParseJson } from '../../../../components/AttributeList/utils';
import { formatDuration } from '../../../TracesPage/tracesApi';
import { attrValueAsString } from '../../attrFormat';
import { severityColor, severityLabel } from '../../severity';
import { clock } from './dockParts';

// Keys already surfaced in the row header — don't repeat them in the expanded
// attribute list.
const LOG_HEADER_KEYS = new Set([
  'event.name',
  'event.timestamp',
  'tool',
  'session.id',
]);
// Anything longer than this gets a "view formatted" link that opens a modal.
// 240 chars fits a couple of inline lines without crowding the dock.
const LONG_VALUE_THRESHOLD = 240;

interface ModalValue {
  key: string;
  raw: string;
}

// Renders one attribute value. Falls back to a "view formatted" link when the
// value is too long for the inline list — the dock would otherwise drown in
// 60 KB tool payloads.
const AttrValue = ({ value, onViewFull }: { value: unknown; onViewFull: (text: string) => void }) => {
  const text = attrValueAsString(value);
  const color = typeof value === 'number' ? 'info.main' : typeof value === 'string' ? 'success.main' : 'text.primary';
  if (text.length <= LONG_VALUE_THRESHOLD) {
    return <Box component="span" sx={{ color, wordBreak: 'break-word' }}>{text}</Box>;
  }
  const preview = text.slice(0, LONG_VALUE_THRESHOLD).replace(/\s+$/, '');
  return (
    <Box component="span" sx={{ color, wordBreak: 'break-word' }}>
      {preview}…{' '}
      <Box
        component="button"
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          onViewFull(text);
        }}
        sx={{ ml: 0.25, px: 0.6, py: 0.05, border: 'none', borderRadius: '4px', bgcolor: (t) => `color-mix(in srgb, ${t.palette.primary.main} 14%, transparent)`, color: 'primary.main', fontFamily: "'Sora', sans-serif", fontSize: 10, fontWeight: 700, letterSpacing: '0.3px', textTransform: 'uppercase', cursor: 'pointer', '&:hover': { bgcolor: (t) => `color-mix(in srgb, ${t.palette.primary.main} 22%, transparent)` } }}
      >
        view formatted ({text.length.toLocaleString()} chars)
      </Box>
    </Box>
  );
};

// Per-row presentation for a log inside the span dock. Click to expand the
// full attribute payload — long values open in a modal that runs the raw text
// through `tryParseJson` (jsonrepair under the hood) so the 60 KB-truncated
// payloads OTLP ships still display as formatted JSON.
const LogEntry = ({ log, spanStartMs }: { log: LogRow; spanStartMs: number }) => {
  const theme = useTheme();
  const [expanded, setExpanded] = useState(false);
  const [modalValue, setModalValue] = useState<ModalValue | null>(null);
  const [copied, setCopied] = useState(false);
  const sev = severityLabel(log.severityNumber);
  const sevColor = severityColor(log.severityNumber);
  const sevPalette = sevColor === 'error' ? theme.palette.error.main : sevColor === 'warning' ? theme.palette.warning.main : sevColor === 'info' ? theme.palette.primary.main : theme.palette.text.disabled;
  const logMs = Date.parse(log.timestamp);
  const offsetNanos = (logMs - spanStartMs) * 1e6;
  const eventName = typeof log.attributes?.['event.name'] === 'string' ? (log.attributes['event.name'] as string) : null;
  const tool = typeof log.attributes?.['tool'] === 'string' ? (log.attributes['tool'] as string) : null;
  const detailEntries = Object.entries(log.attributes ?? {}).filter(([k]) => !LOG_HEADER_KEYS.has(k));
  const hasDetail = detailEntries.length > 0 || Boolean(log.scopeName);
  const parsed = modalValue ? tryParseJson(modalValue.raw) : undefined;
  const displayText = parsed?.value !== undefined ? JSON.stringify(parsed.value, null, 2) : (modalValue?.raw ?? '');
  const closeModal = () => {
    setModalValue(null);
    setCopied(false);
  };
  const copyDisplayed = () => {
    if (navigator.clipboard) {
      void navigator.clipboard.writeText(displayText);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }
  };
  return (
    <Box
      onClick={hasDetail ? () => setExpanded((v) => !v) : undefined}
      sx={{ borderBottom: 1, borderColor: 'divider', cursor: hasDetail ? 'pointer' : 'default', '&:hover': hasDetail ? { bgcolor: 'action.hover' } : undefined }}
    >
      <Box sx={{ display: 'flex', gap: 1, py: 1, alignItems: 'baseline', flexWrap: 'wrap' }}>
        <Box component="span" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: 10.5, color: 'text.disabled', whiteSpace: 'nowrap' }} title={new Date(logMs).toISOString()}>
          T+{formatDuration(offsetNanos)} · {clock(logMs)}
        </Box>
        <Box component="span" sx={{ fontFamily: "'Sora', sans-serif", fontSize: 9.5, fontWeight: 700, color: sevPalette, minWidth: 38 }}>{sev}</Box>
        {eventName ? <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', height: 16, px: 0.75, borderRadius: '4px', bgcolor: (t) => `color-mix(in srgb, ${t.palette.primary.main} 14%, transparent)`, color: 'primary.main', fontFamily: "'JetBrains Mono', monospace", fontSize: 10, fontWeight: 600 }}>{eventName}</Box> : null}
        {tool ? <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', height: 16, px: 0.75, borderRadius: '4px', bgcolor: (t) => `color-mix(in srgb, ${t.palette.info.main} 14%, transparent)`, color: 'info.main', fontFamily: "'JetBrains Mono', monospace", fontSize: 10, fontWeight: 600 }}>{tool}</Box> : null}
        {log.body ? <Box component="span" sx={{ fontSize: 12.5, color: 'text.primary', wordBreak: 'break-word' }}>{log.body}</Box> : null}
      </Box>
      {expanded ? (
        <Box sx={{ pb: 1, pl: 1, display: 'flex', flexDirection: 'column', gap: 0.4 }} onClick={(e) => e.stopPropagation()}>
          {log.scopeName ? (
            <Box sx={{ display: 'grid', gridTemplateColumns: 'minmax(140px,auto) 1fr', gap: 1.5, fontFamily: "'JetBrains Mono', monospace", fontSize: 11.5 }}>
              <Box component="span" sx={{ color: 'text.secondary' }}>scope</Box>
              <Box component="span" sx={{ color: 'text.primary', wordBreak: 'break-word' }}>{log.scopeName}</Box>
            </Box>
          ) : null}
          {detailEntries.map(([k, v]) => (
            <Box key={k} sx={{ display: 'grid', gridTemplateColumns: 'minmax(140px,auto) 1fr', gap: 1.5, fontFamily: "'JetBrains Mono', monospace", fontSize: 11.5 }}>
              <Box component="span" sx={{ color: 'text.secondary' }}>{k}</Box>
              <AttrValue value={v} onViewFull={(text) => setModalValue({ key: k, raw: text })} />
            </Box>
          ))}
        </Box>
      ) : null}
      <Dialog open={modalValue != null} onClose={closeModal} maxWidth="md" fullWidth onClick={(e) => e.stopPropagation()}>
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1, fontFamily: "'JetBrains Mono', monospace", fontSize: 14 }}>
          <Box component="span" sx={{ flex: 1, wordBreak: 'break-word' }}>{modalValue?.key}</Box>
          <Tooltip arrow placement="top" title={copied ? 'Copied' : 'Copy displayed value'}>
            <IconButton size="small" onClick={copyDisplayed} sx={{ color: copied ? 'success.main' : 'text.secondary' }}>
              <ContentCopyIcon sx={{ fontSize: 16 }} />
            </IconButton>
          </Tooltip>
          <IconButton size="small" onClick={closeModal} sx={{ color: 'text.secondary' }}>
            <CloseIcon sx={{ fontSize: 18 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent dividers sx={{ p: 0 }}>
          {parsed?.repaired ? <Alert severity="warning" sx={{ borderRadius: 0 }}>Repaired from truncated JSON — trailing values may be missing or incomplete.</Alert> : null}
          <Box component="pre" sx={{ m: 0, p: 2, fontFamily: "'JetBrains Mono', monospace", fontSize: 12.5, whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: 'text.primary' }}>{displayText}</Box>
        </DialogContent>
      </Dialog>
    </Box>
  );
};

export default LogEntry;
