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
import React from 'react';
import {
  Alert,
  Box,
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import ReactMarkdown from 'react-markdown';
import rehypeSanitize from 'rehype-sanitize';
import type { ValueDialogState } from './types';
import { tryParseJson, isPlainObject } from './utils';
import { parseTaskNotificationEnvelope } from '../../lib/promptSummary';
import { radii } from '../../theme/theme';

export interface ExpandedValueDialogProps {
  state: ValueDialogState | null;
  onClose: () => void;
  renderAttributeList: (attrs: Record<string, unknown>) => React.ReactNode;
}

// Markdown styling for the envelope's summary/note prose — sized to match
// this dialog's own mono/12px body copy rather than react-markdown's unstyled
// defaults. A trimmed-down copy of SpanInspectorDrawer's longValue.tsx
// markdownSx: this content is one or two short sentences, never a full
// assistant response, so there's no need for its heading/list/code rules.
const envelopeProseSx = {
  fontSize: '0.8125rem',
  lineHeight: 1.6,
  color: 'text.primary',
  '& > :first-of-type': { mt: 0 },
  '& > :last-child': { mb: 0 },
  '& p': { m: 0 },
} as const;

// `result` (unlike summary/note) can be a full markdown body — a dispatched
// subagent's own final report, headings/lists/code included — so it gets the
// fuller rule set, the same shape as SpanInspectorDrawer's longValue.tsx
// markdownSx rather than the trimmed-down envelopeProseSx above. No custom
// link component: rehype-sanitize's default schema already restricts `href`
// to safe protocols, the same reasoning AnalyzeTraceDialogView's markdown
// relies on.
const envelopeResultSx = {
  fontSize: '0.8125rem',
  lineHeight: 1.65,
  color: 'text.primary',
  '& > :first-of-type': { mt: 0 },
  '& > :last-child': { mb: 0 },
  '& p': { mt: 0, mb: 1 },
  '& ul, & ol': { mt: 0, mb: 1, pl: 2.5 },
  '& li': { mb: 0.4 },
  '& h1, & h2, & h3': { fontFamily: 'inherit', fontWeight: 700, mt: 1.5, mb: 0.75 },
  '& h1': { fontSize: 16 },
  '& h2': { fontSize: 14.5 },
  '& h3': { fontSize: 13.5 },
  '& code': {
    typography: 'mono',
    fontSize: 11,
    px: 0.5,
    py: 0.1,
    borderRadius: '4px',
    bgcolor: 'action.hover',
  },
  '& pre': {
    typography: 'mono',
    fontSize: 11,
    p: 1.25,
    borderRadius: radii.sm,
    overflow: 'auto',
    bgcolor: 'action.hover',
    mb: 1,
  },
  '& pre code': { p: 0, bgcolor: 'transparent' },
  '& blockquote': {
    borderLeft: 3,
    borderColor: 'divider',
    color: 'text.secondary',
    pl: 1.25,
    ml: 0,
    mb: 1,
  },
  '& strong': { fontWeight: 700 },
} as const;

// A <task-notification> envelope (see lib/promptSummary.ts) is structured
// data, not prose — dumping its raw XML tags in a monospace box (the
// tryParseJson fallback below, which never matches XML) makes a reader parse
// markup to find the one line that actually matters. Rendered as just its
// summary/result/note prose as markdown; `envelope.fields` (task-id,
// tool-use-id, output-file, status, usage, ...) is deliberately NOT shown —
// it's harness bookkeeping a reader opening "view full prompt" has no use
// for, not a fact about the work the subagent did.
const TaskNotificationView = ({
  envelope,
}: {
  envelope: NonNullable<ReturnType<typeof parseTaskNotificationEnvelope>>;
}) => {
  if (!envelope.summary && !envelope.result && !envelope.note) {
    return (
      <Box sx={{ fontSize: '0.8125rem', color: 'text.disabled', fontStyle: 'italic' }}>
        This notification carries no summary or result.
      </Box>
    );
  }
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {envelope.summary || envelope.note ? (
        <Box
          sx={{
            p: 1.5,
            borderRadius: radii.sm,
            bgcolor: 'action.hover',
            display: 'flex',
            flexDirection: 'column',
            gap: 1,
          }}
        >
          {envelope.summary ? (
            <Box sx={envelopeProseSx}>
              <ReactMarkdown rehypePlugins={[rehypeSanitize]}>
                {envelope.summary}
              </ReactMarkdown>
            </Box>
          ) : null}
          {envelope.note ? (
            <Box sx={{ ...envelopeProseSx, color: 'text.secondary' }}>
              <ReactMarkdown rehypePlugins={[rehypeSanitize]}>
                {envelope.note}
              </ReactMarkdown>
            </Box>
          ) : null}
        </Box>
      ) : null}
      {envelope.result ? (
        <Box sx={envelopeResultSx}>
          <ReactMarkdown rehypePlugins={[rehypeSanitize]}>
            {envelope.result}
          </ReactMarkdown>
        </Box>
      ) : null}
    </Box>
  );
};

export const ExpandedValueDialog = ({
  state,
  onClose,
  renderAttributeList,
}: ExpandedValueDialogProps): React.ReactElement => {
  const envelope = state ? parseTaskNotificationEnvelope(state.value) : null;
  const parsed = state && !envelope ? tryParseJson(state.value) : undefined;
  const parsedValue = parsed?.value;
  return (
    <Dialog open={state != null} onClose={onClose} maxWidth="lg" fullWidth>
      <DialogTitle sx={{ typography: 'mono', pr: 6 }}>
        {state?.key}
        <IconButton
          aria-label="close"
          size="small"
          onClick={onClose}
          sx={{ position: 'absolute', right: 12, top: 12 }}
        >
          <CloseIcon fontSize="small" />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers>
        {parsed?.repaired ? (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Repaired from truncated JSON — trailing values may be missing or
            incomplete.
          </Alert>
        ) : null}
        {envelope ? (
          <TaskNotificationView envelope={envelope} />
        ) : isPlainObject(parsedValue) ? (
          renderAttributeList(parsedValue)
        ) : (
          <Box
            sx={{
              typography: 'mono',
              p: 1,
              borderRadius: radii.sm,
              fontSize: '0.75rem',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              bgcolor: 'action.hover',
            }}
          >
            {parsedValue !== undefined
              ? JSON.stringify(parsedValue, null, 2)
              : state?.value}
          </Box>
        )}
      </DialogContent>
    </Dialog>
  );
};
