import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Alert,
  Box,
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
  Tooltip,
  alpha,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import ReactMarkdown from 'react-markdown';
import { tryParseJson } from '../../../../components/AttributeList/utils';
import { attrValueAsString } from '../../attrFormat';

// One truncate-and-view-formatted path for every long value in the inspector
// drawer: log attributes, span/tool attributes, and event attributes. It used to
// exist only inside LogEntry, which is why a 4KB stderr on a process.exit event
// (or a heredoc full_command on a Bash span) still pushed the drawer's attribute
// grid out of shape — the two places most likely to hold one had no clamp at all.
//
// The modal is hosted once, at the drawer root, rather than per row: a Dialog per
// log line meant N mounted dialogs in a long Logs section, and the event/attribute
// grids have no row component to hang one on.

// Log rows span the drawer's full width, so they can afford a couple of inline
// lines before clamping.
export const LONG_VALUE_LOG = 240;
// The event and attribute grids give the value a much narrower column (the key
// takes up to 42%), so they clamp sooner — same store, same modal, tighter budget.
export const LONG_VALUE_ATTR = 110;

type LongValueFormat = 'json' | 'markdown';

interface LongValueRequest {
  key: string;
  raw: string;
  /** 'json' (default) runs the value through tryParseJson/jsonrepair before
   *  display; 'markdown' renders it through ReactMarkdown instead — for
   *  claude_code.assistant_response logs, whose `response` body is prose, not
   *  a payload the JSON repair path would ever successfully parse. */
  format?: LongValueFormat;
}

const LongValueModalContext = createContext<((request: LongValueRequest) => void) | null>(null);

// Modal-body typography for rendered markdown — sized and spaced to match the
// drawer's existing `mono`/13px body copy rather than react-markdown's
// unstyled defaults.
const markdownSx = {
  p: 2,
  fontSize: 13.5,
  lineHeight: 1.65,
  color: 'text.primary',
  '& > :first-of-type': { mt: 0 },
  '& > :last-child': { mb: 0 },
  '& p': { mt: 0, mb: 1.25 },
  '& ul, & ol': { mt: 0, mb: 1.25, pl: 2.5 },
  '& li': { mb: 0.5 },
  '& h1, & h2, & h3': { fontFamily: 'inherit', fontWeight: 700, mt: 2, mb: 1 },
  '& h1': { fontSize: 18 },
  '& h2': { fontSize: 15.5 },
  '& h3': { fontSize: 14 },
  '& code': {
    typography: 'mono',
    fontSize: 12,
    px: 0.6,
    py: 0.1,
    borderRadius: '5px',
    bgcolor: (t: { palette: { action: { hover: string } } }) => t.palette.action.hover,
  },
  '& pre': {
    typography: 'mono',
    fontSize: 12,
    p: 1.5,
    borderRadius: '9px',
    overflow: 'auto',
    bgcolor: (t: { palette: { action: { hover: string } } }) => t.palette.action.hover,
    mb: 1.25,
  },
  '& pre code': { p: 0, bgcolor: 'transparent' },
  '& a': { color: 'primary.main' },
  '& blockquote': {
    borderLeft: 3,
    borderColor: 'divider',
    color: 'text.secondary',
    pl: 1.5,
    ml: 0,
    mb: 1.25,
  },
  '& strong': { fontWeight: 700 },
} as const;

// Hosts the single modal and hands its opener down through context, so a value
// deep inside AttrRows or SpanEventsList can ask for it without every grid
// growing an onExpand prop.
export const LongValueModalProvider = ({ children }: { children: ReactNode }) => {
  const [request, setRequest] = useState<LongValueRequest | null>(null);
  const [copied, setCopied] = useState(false);
  const open = useCallback((next: LongValueRequest) => {
    setRequest(next);
    setCopied(false);
  }, []);
  const close = () => {
    setRequest(null);
    setCopied(false);
  };
  const isMarkdown = request?.format === 'markdown';
  // Same jsonrepair-backed parse the Logs page uses, so the 60KB-truncated OTLP
  // payloads still display as formatted JSON; non-JSON text passes through raw.
  // Skipped for markdown requests — prose is never a JSON repair candidate.
  const parsed = request && !isMarkdown ? tryParseJson(request.raw) : undefined;
  const displayText = isMarkdown
    ? (request?.raw ?? '')
    : parsed?.value !== undefined
      ? JSON.stringify(parsed.value, null, 2)
      : (request?.raw ?? '');
  const copyDisplayed = () => {
    if (navigator.clipboard) {
      void navigator.clipboard.writeText(displayText);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }
  };
  return (
    <LongValueModalContext.Provider value={open}>
      {children}
      <Dialog
        open={request != null}
        onClose={close}
        maxWidth="md"
        fullWidth
        onClick={(e) => e.stopPropagation()}
      >
        <DialogTitle
          sx={{ display: 'flex', alignItems: 'center', gap: 1, typography: 'mono', fontSize: 14 }}
        >
          <Box component="span" sx={{ flex: 1, wordBreak: 'break-word' }}>
            {request?.key}
          </Box>
          <Tooltip arrow placement="top" title={copied ? 'Copied' : 'Copy displayed value'}>
            <IconButton
              size="small"
              onClick={copyDisplayed}
              sx={{ color: copied ? 'success.main' : 'text.secondary' }}
            >
              <ContentCopyIcon sx={{ fontSize: 16 }} />
            </IconButton>
          </Tooltip>
          <IconButton size="small" onClick={close} sx={{ color: 'text.secondary' }}>
            <CloseIcon sx={{ fontSize: 18 }} />
          </IconButton>
        </DialogTitle>
        <DialogContent dividers sx={{ p: 0 }}>
          {isMarkdown ? (
            <Box sx={markdownSx}>
              <ReactMarkdown>{displayText}</ReactMarkdown>
            </Box>
          ) : (
            <>
              {parsed?.repaired ? (
                <Alert severity="warning" sx={{ borderRadius: 0 }}>
                  Repaired from truncated JSON — trailing values may be missing or
                  incomplete.
                </Alert>
              ) : null}
              <Box
                component="pre"
                sx={{
                  m: 0,
                  p: 2,
                  typography: 'mono',
                  fontSize: 12.5,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                  color: 'text.primary',
                }}
              >
                {displayText}
              </Box>
            </>
          )}
        </DialogContent>
      </Dialog>
    </LongValueModalContext.Provider>
  );
};

// Null outside a provider — LongAttrValue then renders the clamped preview with
// no link rather than throwing, so a section reused elsewhere still works.
export const useLongValueModal = () => useContext(LongValueModalContext);

interface LongAttrValueProps {
  attrKey: string;
  value: unknown;
  /** Character budget before clamping. LONG_VALUE_ATTR / LONG_VALUE_LOG. */
  limit?: number;
  /** sx color for the value text — callers keep their own type-based hues. */
  color?: string;
  /** Pre-formatted display text (e.g. a localized number). */
  text?: string;
  /** Show the "view formatted" button unconditionally, even under `limit` —
   *  for claude_code.assistant_response's `response` attribute, whose value
   *  is always worth opening in the (markdown) modal, short or not. */
  force?: boolean;
  /** Modal rendering mode for this value. Default 'json'. */
  format?: LongValueFormat;
}

// The value cell itself: prints the value, or — past `limit`, or when `force` —
// a preview plus a "view formatted (N chars)" button that opens the modal.
export const LongAttrValue = ({
  attrKey,
  value,
  limit = LONG_VALUE_ATTR,
  color = 'text.primary',
  text,
  force = false,
  format = 'json',
}: LongAttrValueProps) => {
  const openModal = useLongValueModal();
  const full = useMemo(() => text ?? attrValueAsString(value), [text, value]);
  const clamped = full.length > limit;
  if (!clamped && !force) {
    return (
      <Box component="span" sx={{ color, wordBreak: 'break-word' }}>
        {full}
      </Box>
    );
  }
  const preview = clamped ? `${full.slice(0, limit).replace(/\s+$/, '')}…` : full;
  return (
    <Box component="span" sx={{ color, wordBreak: 'break-word' }}>
      {preview}{' '}
      {openModal ? (
        <Box
          component="button"
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            openModal({ key: attrKey, raw: full, format });
          }}
          sx={{
            ml: 0.25,
            px: 0.6,
            py: 0.05,
            border: 'none',
            borderRadius: '4px',
            bgcolor: (t) => alpha(t.palette.primary.main, 0.14),
            color: 'primary.main',
            typography: 'eyebrowSm',
            cursor: 'pointer',
            '&:hover': { bgcolor: (t) => alpha(t.palette.primary.main, 0.22) },
          }}
        >
          view formatted ({full.length.toLocaleString()} chars)
        </Box>
      ) : null}
    </Box>
  );
};
