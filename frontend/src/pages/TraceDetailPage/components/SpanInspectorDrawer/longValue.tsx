import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ComponentPropsWithoutRef, ReactNode } from 'react';
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
import remarkGfm from 'remark-gfm';
import 'github-markdown-css/github-markdown-dark.css';
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

// Only absolute http(s) links are safe to open from the drawer: a relative link
// in an assistant response (e.g. a repo-relative file path the model wrote as
// markdown) would resolve against the dashboard's own URL and either 404 or,
// worse, navigate the SPA to an unintended in-app route. Relative links render
// as plain, unclickable text instead of an <a>.
const isAbsoluteHttpUrl = (href: string | undefined): href is string => {
  if (!href) {
    return false;
  }
  try {
    return ['http:', 'https:'].includes(new URL(href).protocol);
  } catch {
    return false;
  }
};

const MarkdownLink = ({ href, children, ...rest }: ComponentPropsWithoutRef<'a'>) => {
  if (!isAbsoluteHttpUrl(href)) {
    return <span {...rest}>{children}</span>;
  }
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" {...rest}>
      {children}
    </a>
  );
};

// Log rows span the drawer's full width, so they can afford a couple of inline
// lines before clamping.
export const LONG_VALUE_LOG = 240;
// The event and attribute grids give the value a much narrower column (the key
// takes up to 42%), so they clamp sooner — same store, same modal, tighter budget.
export const LONG_VALUE_ATTR = 110;

interface LongValueRequest {
  key: string;
  raw: string;
  type?: 'json' | 'markdown';
}

const LongValueModalContext = createContext<((request: LongValueRequest) => void) | null>(null);

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
  const isMarkdown = request?.type === 'markdown';
  const parsed = request && !isMarkdown ? tryParseJson(request.raw) : undefined;
  const rawDisplayText =
    parsed?.value !== undefined
      ? JSON.stringify(parsed.value, null, 2)
      : (request?.raw ?? '');
  // Claude Code emits assistant-response bullets as a leading en dash ("– item")
  // rather than markdown's hyphen syntax, so remark-gfm parses them as plain
  // paragraphs instead of a list. Only a line-leading "– " is rewritten — an em/en
  // dash used mid-sentence (e.g. "greyed out — cursor not-allowed") is untouched.
  const displayText = isMarkdown
    ? rawDisplayText.replace(/^([ \t]*)[–—]( +)/gm, '$1-$2')
    : rawDisplayText;
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
          {parsed?.repaired && !isMarkdown ? (
            <Alert severity="warning" sx={{ borderRadius: 0 }}>
              Repaired from truncated JSON — trailing values may be missing or
              incomplete.
            </Alert>
          ) : null}
          {isMarkdown ? (
            <Box
              className="markdown-body"
              sx={{
                m: 0,
                p: 2,
                bgcolor: 'transparent',
              }}
            >
              <ReactMarkdown remarkPlugins={[remarkGfm]} components={{ a: MarkdownLink }}>
                {displayText}
              </ReactMarkdown>
            </Box>
          ) : (
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
}

// The value cell itself: prints the value, or — past `limit` — a clamped preview
// plus a "view formatted (N chars)" button that opens the modal.
export const LongAttrValue = ({
  attrKey,
  value,
  limit = LONG_VALUE_ATTR,
  color = 'text.primary',
  text,
}: LongAttrValueProps) => {
  const openModal = useLongValueModal();
  const full = useMemo(() => text ?? attrValueAsString(value), [text, value]);
  if (full.length <= limit) {
    return (
      <Box component="span" sx={{ color, wordBreak: 'break-word' }}>
        {full}
      </Box>
    );
  }
  const preview = full.slice(0, limit).replace(/\s+$/, '');
  return (
    <Box component="span" sx={{ color, wordBreak: 'break-word' }}>
      {preview}…{' '}
      {openModal ? (
        <Box
          component="button"
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            openModal({ key: attrKey, raw: full });
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
