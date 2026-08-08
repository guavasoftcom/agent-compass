import { useState } from 'react';
import type { ReactNode } from 'react';
import { Box, Typography, useTheme } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import type { LogRow, SpanRow } from '../../../../api';
import { formatDuration, formatUsd } from '../../../TracesPage/tracesApi';
import { type TokenBreakdown } from '../../../TracesPage/tokenBreakdown';
import { clock } from './drawerParts';
import { useResizableWidth } from './useResizableWidth';
import CollapsibleSection from './CollapsibleSection';
import ErrorSection from './ErrorSection';
import LogEntry from './LogEntry';
import TokensSection from './TokensSection';
import SpanAttributeSections from './SpanAttributeSections';
import SpanEventsList from './SpanEventsList';
import { radii } from '../../../../theme/theme';

export interface SpanInspectorSelection {
  span: SpanRow;
  selfTimeNanos: number;
  tokens: TokenBreakdown;
  logs: LogRow[];
  // Real, billed cost for this span (costOfSpan(span) — see spanCost.ts). Not
  // an estimate: the summed cost_usd of the api_request logs stamped with this
  // span. 0 when none was logged against it.
  costUsd: number;
  // Span nav — the span's position among the waterfall's currently rendered
  // rows. Nav buttons are omitted entirely when there's nowhere to step
  // (waterfallCount <= 1).
  waterfallIndex: number;
  waterfallCount: number;
}

interface Props {
  // Null renders the drawer closed (width 0). The component stays mounted
  // either way so the open/close width transition can run, and so the last
  // dragged width survives across span selections.
  selection: SpanInspectorSelection | null;
  onClose: () => void;
  onPreviousSpan: () => void;
  onNextSpan: () => void;
}

const DrawerContent = ({ selection }: { selection: SpanInspectorSelection }) => {
  const theme = useTheme();
  const { span, selfTimeNanos, tokens, logs, costUsd } = selection;

  const startMs = Date.parse(span.startTimestamp);
  const endMs = Date.parse(span.endTimestamp);
  const durationMs = span.durationNanos / 1e6;
  const selfMs = selfTimeNanos / 1e6;
  const selfPercent = durationMs > 0 ? Math.round((selfMs / durationMs) * 100) : 100;

  const meta: Array<[string, ReactNode]> = [
    ['span id', span.spanId],
    ['kind', span.kind ?? '—'],
    ['scope', span.scopeName ?? '—'],
    [
      'status',
      <Box
        key="st"
        component="span"
        sx={{
          color: span.statusCode === 'error' ? 'error.main' : 'success.main',
        }}
      >
        {span.statusCode ?? 'ok'}
      </Box>,
    ],
    ['started', clock(startMs)],
    ['ended', clock(endMs)],
    ['duration', formatDuration(span.durationNanos)],
    ...(costUsd > 0
      ? ([
          [
            'cost',
            <Box
              key="cost"
              component="span"
              sx={{ color: 'warning.main', fontWeight: 700 }}
            >
              {formatUsd(costUsd)}
            </Box>,
          ],
        ] as Array<[string, ReactNode]>)
      : []),
  ];

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        gap: 1.6,
        minWidth: 0,
      }}
    >
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'auto 1fr',
          columnGap: 1.75,
          rowGap: 0.9,
        }}
      >
        {meta.map(([k, v]) => (
          <Box key={k} sx={{ display: 'contents' }}>
            <Box
              component="span"
              sx={{ typography: 'mono', fontSize: 12, color: 'text.secondary', whiteSpace: 'nowrap' }}
            >
              {k}
            </Box>
            <Box
              component="span"
              sx={{ typography: 'mono', fontSize: 12, color: 'text.primary', wordBreak: 'break-word' }}
            >
              {v}
            </Box>
          </Box>
        ))}
      </Box>

      {selfMs !== durationMs ? (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            px: 1.4,
            py: 1,
            border: 1,
            borderColor: 'divider',
            borderRadius: radii.sm,
            fontSize: 11.5,
            color: 'text.secondary',
          }}
        >
          self time <b>{formatDuration(selfTimeNanos)}</b>
          <Box
            sx={{
              flex: 1,
              height: 6,
              minWidth: 40,
              borderRadius: radii.sm,
              bgcolor: 'action.hover',
              overflow: 'hidden',
            }}
          >
            <Box
              sx={{
                width: `${selfPercent}%`,
                height: '100%',
                borderRadius: radii.sm,
                background: `linear-gradient(90deg, ${theme.palette.warning.main}, ${theme.palette.warning.light})`,
              }}
            />
          </Box>
          <b>{selfPercent}%</b>
        </Box>
      ) : null}

      <ErrorSection span={span} logs={logs} />

      <TokensSection tokens={tokens} costUsd={costUsd} />
      <SpanAttributeSections attributes={span.attributes ?? undefined} />
      <SpanEventsList events={span.events ?? undefined} spanStartMs={startMs} />
      {logs.length ? (
        <CollapsibleSection title="Logs" count={logs.length} defaultCollapsed>
          <Box sx={{ display: 'flex', flexDirection: 'column' }}>
            {logs.map((l) => (
              <LogEntry key={l.id} log={l} spanStartMs={startMs} />
            ))}
          </Box>
        </CollapsibleSection>
      ) : null}
    </Box>
  );
};

// Right-side inspector drawer: a flex sibling of the waterfall card (no
// scrim/overlay — the waterfall stays visible and scrollable), sliding open
// via a width transition and resizable by dragging its left edge.
const SpanInspectorDrawer = ({
  selection,
  onClose,
  onPreviousSpan,
  onNextSpan,
}: Props) => {
  const { drawerRef, widthPx, isResizing, onGripDown } = useResizableWidth();
  const open = selection != null;

  // Keep the last selection rendered while the drawer animates closed, so the
  // content slides out instead of vanishing the moment the span is deselected.
  // Guarded render-phase state adjustment — React's documented pattern for
  // storing information from previous renders. Compared by span id, not object
  // identity: the parent builds a fresh selection literal every render, so an
  // identity check re-ran this setState (and so a second render pass) on every
  // parent render.
  const [lastSelection, setLastSelection] =
    useState<SpanInspectorSelection | null>(null);
  if (
    selection != null
    && selection.span.spanId !== lastSelection?.span.spanId
  ) {
    setLastSelection(selection);
  }
  const rendered = selection ?? lastSelection;

  return (
    <Box
      ref={drawerRef}
      // `inert` while closed keeps the leftover content out of the tab order and
      // the accessibility tree during the close animation; the transition end
      // then drops it entirely, so a closed drawer has no hidden focus targets
      // behind `width: 0`.
      inert={!open}
      onTransitionEnd={(event) => {
        if (
          !open
          && event.target === event.currentTarget
          && event.propertyName === 'width'
        ) {
          setLastSelection(null);
        }
      }}
      sx={{
        position: 'relative',
        width: open ? (widthPx != null ? `${widthPx}px` : 'min(440px, 42vw)') : 0,
        ml: open ? 2 : 0,
        flexShrink: 0,
        overflow: 'hidden',
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
        transition: isResizing ? 'none' : 'width 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
        border: open ? 1 : 0,
        borderColor: 'divider',
        borderRadius: radii.xl,
        bgcolor: 'background.paper',
      }}
    >
      {open ? (
        <Box
          onMouseDown={onGripDown}
          title="Drag to resize"
          sx={{
            position: 'absolute',
            left: 0,
            top: 0,
            bottom: 0,
            width: 18,
            cursor: 'col-resize',
            zIndex: 2,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            '&::before': {
              content: '""',
              width: 3,
              height: 44,
              borderRadius: radii.xs,
              bgcolor: 'divider',
              transition: 'background-color 0.12s ease',
            },
            '&:hover::before': { bgcolor: 'primary.main' },
          }}
        />
      ) : null}
      {rendered ? (
        <>
          <Box
            sx={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: 1.25,
              px: 2,
              py: 1.75,
              borderBottom: 1,
              borderColor: 'divider',
              flexShrink: 0,
            }}
          >
            <Typography
              sx={{
                flex: 1,
                minWidth: 0,
                typography: 'mono',
                fontSize: 13,
                fontWeight: 600,
                wordBreak: 'break-all',
              }}
            >
              {rendered.span.name}
            </Typography>
            {rendered.waterfallCount > 1 ? (
              <Box
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 0.5,
                  flexShrink: 0,
                }}
              >
                <Box
                  component="button"
                  disabled={rendered.waterfallIndex <= 0}
                  onClick={onPreviousSpan}
                  title="Previous span (↑)"
                  sx={{
                    display: 'grid',
                    placeItems: 'center',
                    width: 24,
                    height: 24,
                    border: 1,
                    borderColor: 'divider',
                    borderRadius: radii.xs,
                    bgcolor: 'background.paper',
                    cursor: 'pointer',
                    color: 'text.secondary',
                    '&:hover:not(:disabled)': {
                      color: 'primary.main',
                      borderColor: 'primary.main',
                    },
                    '&:disabled': { opacity: 0.4, cursor: 'default' },
                  }}
                >
                  <ExpandLessIcon sx={{ fontSize: 15 }} />
                </Box>
                <Typography
                  sx={{
                    typography: 'mono',
                    fontSize: 10,
                    color: 'text.disabled',
                    whiteSpace: 'nowrap',
                    px: 0.25,
                  }}
                >
                  {rendered.waterfallIndex + 1} / {rendered.waterfallCount}
                </Typography>
                <Box
                  component="button"
                  disabled={
                    rendered.waterfallIndex >= rendered.waterfallCount - 1
                  }
                  onClick={onNextSpan}
                  title="Next span (↓)"
                  sx={{
                    display: 'grid',
                    placeItems: 'center',
                    width: 24,
                    height: 24,
                    border: 1,
                    borderColor: 'divider',
                    borderRadius: radii.xs,
                    bgcolor: 'background.paper',
                    cursor: 'pointer',
                    color: 'text.secondary',
                    '&:hover:not(:disabled)': {
                      color: 'primary.main',
                      borderColor: 'primary.main',
                    },
                    '&:disabled': { opacity: 0.4, cursor: 'default' },
                  }}
                >
                  <ExpandMoreIcon sx={{ fontSize: 15 }} />
                </Box>
              </Box>
            ) : null}
            <Box
              component="button"
              onClick={onClose}
              sx={{
                display: 'grid',
                placeItems: 'center',
                width: 28,
                height: 28,
                flexShrink: 0,
                border: 1,
                borderColor: 'divider',
                borderRadius: radii.xs,
                bgcolor: 'background.paper',
                cursor: 'pointer',
                color: 'text.secondary',
                '&:hover': { color: 'primary.main', borderColor: 'primary.main' },
              }}
            >
              <CloseIcon sx={{ fontSize: 16 }} />
            </Box>
          </Box>
          <Box
            // Marks the drawer's own scroll container: the page's ArrowUp/
            // ArrowDown span-nav listener skips events originating in here so
            // arrow keys scroll this column instead of swapping spans.
            data-drawer-scroll=""
            sx={{
              flex: 1,
              minHeight: 0,
              overflow: 'auto',
              p: '16px 18px 20px',
            }}
          >
            {/* Keyed by span id: section collapse and log-row expand state reset per span selection. */}
            <DrawerContent key={rendered.span.spanId} selection={rendered} />
          </Box>
        </>
      ) : null}
    </Box>
  );
};

export default SpanInspectorDrawer;
