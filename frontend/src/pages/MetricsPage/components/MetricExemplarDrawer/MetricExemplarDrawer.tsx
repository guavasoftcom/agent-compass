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
import { useMemo, useState, type ReactNode } from 'react';
import { Box, Drawer, alpha } from '@mui/material';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import CloseIcon from '@mui/icons-material/Close';
import type { SpanRow, TraceRow } from '../../../../api';
import { AttributeList } from '../../../../components/AttributeList';
import { formatTimestamp } from '../../../../lib/format';
import { gradients, neutralColors } from '../../../../theme/colors';
import { backdropGradient, radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import type { ChipFamily } from '../../../TraceDetailPage/chipVisibility';
import SpanWaterfallRow from '../../../TraceDetailPage/components/SpanWaterfallRow';
import { formatDuration } from '../../../TracesPage/tracesApi';
import { EM_DASH, formatDistributionValue, type DistributionUnit } from '../MetricDistributionCard/distributionScatter';
import {
  buildExemplarWaterfall,
  exemplarStatusOf,
  summarizeExemplarModels,
  type ExemplarStatus,
} from './exemplarWaterfall';

// Slide timing shared with SessionDetailDrawer, so the two peek drawers feel like one family.
const SLIDE_DURATION_MS = 260;
const SLIDE_EASING = 'cubic-bezier(.22,.8,.24,1)';

// The peek keeps the name, model and tool chips (what tells one row from the next) and drops the
// token / cache / cost ones: the stat row and the full trace page carry those figures. The `call N`
// badge is dropped too (showCallNumber below): nothing here cites call numbers.
const HIDDEN_CHIP_FAMILIES: Set<ChipFamily> = new Set(['tok', 'cr', 'cost']);

// A tight name column leaves the bar room in a ~550px drawer; the row's own ellipsis handles overflow.
const WATERFALL_GRID_COLUMNS = 'minmax(0, 48%) 1fr';

/** One request the distribution plot marked as an exemplar, plus whatever its trace has loaded so far. */
export interface MetricExemplar {
  traceId: string;
  /** The request's own `llm_request` span in the trace, for the hand-off to land on; null if unknown. */
  spanId?: string | null;
  /** Full metric name the exemplar was sampled from (`claude_code.token.usage`). */
  metricName: string;
  unit: DistributionUnit;
  /** The request's own tokens / USD from the distribution point; null if the point is not in the payload. */
  value: number | null;
  /** ISO-8601 instant of the request; null if the point is not in the payload. */
  timestamp: string | null;
  /** Undefined while loading; null when the backend has no summary for this trace id. */
  summary?: TraceRow | null;
  spans?: SpanRow[];
  isLoading: boolean;
  errorMessage: string | null;
}

export interface MetricExemplarDrawerProps {
  /** Null closes the drawer; the last non-null exemplar stays rendered through the slide-out. */
  exemplar: MetricExemplar | null;
  onClose: () => void;
  /** The hand-off to the full Trace Detail page, for when a quick peek is not enough. */
  onOpenInTraces: (traceId: string, spanId: string | null) => void;
}

const STAT_LABEL_SX = {
  fontFamily: fontFamilies.display,
  fontSize: 11,
  fontWeight: 600,
  letterSpacing: '.5px',
  textTransform: 'uppercase',
  color: 'text.disabled',
} as const;

const SECTION_TITLE_SX = {
  fontFamily: fontFamilies.display,
  fontSize: 12,
  fontWeight: 600,
  letterSpacing: '.3px',
  color: 'text.secondary',
} as const;

const Stat = ({ label, children, isAccent = false }: { label: string; children: ReactNode; isAccent?: boolean }) => (
  <Box>
    <Box sx={STAT_LABEL_SX}>{label}</Box>
    <Box
      sx={{
        mt: 0.75,
        fontFamily: fontFamilies.display,
        fontSize: 18,
        fontWeight: 800,
        letterSpacing: '-.3px',
        color: isAccent ? 'primary.main' : 'text.primary',
      }}
    >
      {children}
    </Box>
  </Box>
);

const STATUS_COLOR_KEY: Record<ExemplarStatus, 'success' | 'error' | 'warning'> = {
  ok: 'success',
  error: 'error',
  running: 'warning',
};

const StatusBadge = ({ status }: { status: ExemplarStatus | null }) => {
  if (status === null) {
    return <>{EM_DASH}</>;
  }
  const colorKey = STATUS_COLOR_KEY[status];
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-block',
        px: 1.1,
        py: 0.5,
        borderRadius: radii.xs,
        fontFamily: fontFamilies.display,
        fontSize: 11,
        fontWeight: 700,
        letterSpacing: '.5px',
        textTransform: 'uppercase',
        color: `${colorKey}.main`,
        bgcolor: (t) => alpha(t.palette[colorKey].main, 0.16),
      }}
    >
      {status}
    </Box>
  );
};

const ExemplarHeader = ({
  exemplar,
  onClose,
}: {
  exemplar: MetricExemplar;
  onClose: () => void;
}) => (
  <Box sx={{ px: 2.75, py: 2.5, flexShrink: 0, borderBottom: 1, borderColor: 'divider' }}>
    <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 1.5 }}>
      <Box sx={{ minWidth: 0 }}>
        <Box
          sx={{
            mb: 1,
            fontFamily: fontFamilies.display,
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '1.4px',
            textTransform: 'uppercase',
            color: 'primary.main',
          }}
        >
          Exemplar → Trace
        </Box>
        <Box
          sx={{
            fontFamily: fontFamilies.mono,
            fontSize: 15,
            fontWeight: 600,
            color: 'text.primary',
            wordBreak: 'break-all',
          }}
        >
          {`trace ${exemplar.traceId}`}
        </Box>
      </Box>
      <Box
        component="button"
        type="button"
        onClick={onClose}
        aria-label="Close exemplar trace"
        sx={{
          display: 'grid',
          placeItems: 'center',
          width: 32,
          height: 32,
          flexShrink: 0,
          border: 1,
          borderColor: 'divider',
          borderRadius: radii.xs,
          bgcolor: 'background.paper',
          color: 'text.secondary',
          cursor: 'pointer',
          '&:hover': { color: 'error.main', borderColor: (t) => alpha(t.palette.error.main, 0.4) },
        }}
      >
        <CloseIcon sx={{ fontSize: 18 }} />
      </Box>
    </Box>
    <Box sx={{ mt: 1.5, fontSize: 12.5, lineHeight: 1.5, color: 'text.secondary' }}>
      Sampled from{' '}
      <Box component="b" sx={{ color: 'text.primary', fontWeight: 600 }}>
        {exemplar.metricName}
      </Box>
      {exemplar.timestamp ? (
        <>
          {' · recorded '}
          <Box component="b" sx={{ color: 'text.primary', fontWeight: 600 }}>
            {formatTimestamp(exemplar.timestamp)}
          </Box>
        </>
      ) : null}
    </Box>
  </Box>
);

const ExemplarStats = ({ exemplar, models, status }: { exemplar: MetricExemplar; models: string | null; status: ExemplarStatus | null }) => {
  const { summary, spans, unit, value } = exemplar;
  const spanCount = summary?.spanCount ?? (spans && spans.length > 0 ? spans.length : null);
  return (
    <Box
      sx={{
        display: 'flex',
        flexWrap: 'wrap',
        gap: 3,
        px: 2.75,
        py: 2,
        flexShrink: 0,
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Stat label={unit === 'USD' ? 'Cost' : 'Tokens'} isAccent>
        {value === null ? EM_DASH : formatDistributionValue(value, unit)}
      </Stat>
      <Stat label="Duration">{summary ? formatDuration(summary.durationNanos) : EM_DASH}</Stat>
      <Stat label="Spans">{spanCount ?? EM_DASH}</Stat>
      <Stat label="Model">{models ?? EM_DASH}</Stat>
      <Stat label="Status">
        <StatusBadge status={status} />
      </Stat>
    </Box>
  );
};

const NOOP = () => undefined;

const ExemplarWaterfall = ({ exemplar }: { exemplar: MetricExemplar }) => {
  const { spans, isLoading, errorMessage } = exemplar;
  const waterfall = useMemo(() => (spans ? buildExemplarWaterfall(spans) : null), [spans]);

  if (errorMessage) {
    return (
      <Box role="alert" sx={{ py: 1.5, fontSize: 12.5, color: 'error.main' }}>
        {errorMessage}
      </Box>
    );
  }
  if (isLoading || waterfall === null) {
    return <Box sx={{ py: 1.5, fontSize: 12.5, color: 'text.secondary' }}>Loading trace…</Box>;
  }
  if (waterfall.rows.length === 0) {
    return (
      <Box sx={{ py: 1.5, fontSize: 12.5, color: 'text.secondary' }}>
        No spans are recorded for this trace.
      </Box>
    );
  }
  return (
    <Box
      sx={{
        // The peek is read-only: these rows are not selectable here, so drop the pointer.
        '& [data-span]': { cursor: 'default' },
        borderTop: 1,
        borderColor: 'divider',
      }}
    >
      {waterfall.rows.map((row) => (
        <SpanWaterfallRow
          key={row.span.spanId}
          span={row.span}
          depth={row.depth}
          hasChildren={false}
          isCollapsed={false}
          isSelected={false}
          indexLabel={row.indexLabel}
          descendantErrorCount={row.descendantErrorCount}
          costUsd={0}
          isRollupCost={false}
          chipsOff={HIDDEN_CHIP_FAMILIES}
          showCallNumber={false}
          gridColumns={WATERFALL_GRID_COLUMNS}
          left={row.left}
          right={row.right}
          width={row.width}
          onToggleCollapse={NOOP}
          onSelect={NOOP}
        />
      ))}
      {waterfall.hiddenSpanCount > 0 ? (
        <Box sx={{ pt: 1.25, fontSize: 11.5, color: 'text.disabled' }}>
          {`+${waterfall.hiddenSpanCount} more span${waterfall.hiddenSpanCount === 1 ? '' : 's'} — open the trace to see them`}
        </Box>
      ) : null}
    </Box>
  );
};

// What is actually known about this exemplar. The distribution payload carries only a time and a
// value per request, so the rest comes from the trace summary when it loaded.
const exemplarAttributesOf = (exemplar: MetricExemplar): Record<string, unknown> => {
  const { traceId, unit, value, timestamp, summary } = exemplar;
  const attributes: Record<string, unknown> = { 'trace.id': traceId };
  if (value !== null) {
    attributes[unit === 'USD' ? 'request.cost_usd' : 'request.tokens'] = value;
  }
  if (timestamp) {
    attributes['request.timestamp'] = timestamp;
  }
  if (summary?.sessionId) {
    attributes['session.id'] = summary.sessionId;
  }
  if (summary?.rootSpanName) {
    attributes['root.span'] = summary.rootSpanName;
  }
  return attributes;
};

/**
 * Right-side quick peek at one exemplar request's trace, opened from a ringed dot on the
 * distribution scatter: identity, the request's own value, the trace's duration / span count /
 * model / status, a compact span waterfall and the exemplar's attributes.
 *
 * It is deliberately a drawer and not a navigation: the exploration loop the scatter exists for
 * is "click a point, glance at its trace, close, click the next". The footer's "Open in Traces"
 * is the additive hand-off to the full Trace Detail page.
 */
const MetricExemplarDrawer = ({ exemplar, onClose, onOpenInTraces }: MetricExemplarDrawerProps) => {
  const open = exemplar != null;

  // Keep the last exemplar rendered while the drawer slides closed, so the content leaves with the
  // panel instead of vanishing the instant it is deselected. Guarded render-phase state
  // adjustment (React's documented previous-render pattern); the parent memoizes `exemplar`, so
  // comparing by identity settles instead of looping.
  const [lastExemplar, setLastExemplar] = useState<MetricExemplar | null>(null);
  if (exemplar != null && exemplar !== lastExemplar) {
    setLastExemplar(exemplar);
  }
  const rendered = exemplar ?? lastExemplar;

  const models = rendered?.spans ? summarizeExemplarModels(rendered.spans) : null;
  const status = rendered ? exemplarStatusOf(rendered.summary, rendered.spans) : null;

  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      transitionDuration={SLIDE_DURATION_MS}
      slotProps={{
        transition: {
          easing: SLIDE_EASING,
          // The closed exemplar is only dropped once the panel is fully gone.
          onExited: () => setLastExemplar(null),
        },
        backdrop: {
          sx: {
            bgcolor: (t) => alpha(neutralColors.shadowDeep, t.palette.mode === 'dark' ? 0.6 : 0.45),
          },
        },
        paper: {
          sx: {
            width: 560,
            maxWidth: '94vw',
            display: 'flex',
            flexDirection: 'column',
            borderRadius: 0,
            borderLeft: 1,
            borderColor: 'divider',
            bgcolor: 'background.default',
            // The aurora glow is painted on <body> and fixed, so a panel above it would read as a flat slab.
            backgroundImage: (t) => backdropGradient(t.palette.mode),
            backgroundRepeat: 'no-repeat',
            boxShadow: (t) =>
              `-28px 0 60px ${alpha(
                t.palette.mode === 'dark' ? neutralColors.black : neutralColors.shadowIndigo,
                t.palette.mode === 'dark' ? 0.5 : 0.3,
              )}`,
          },
        },
      }}
    >
      {rendered ? (
        <>
          <ExemplarHeader exemplar={rendered} onClose={onClose} />
          <ExemplarStats exemplar={rendered} models={models} status={status} />
          <Box sx={{ flex: 1, minHeight: 0, overflowY: 'auto', px: 2.75, py: 2.25 }}>
            <Box sx={{ ...SECTION_TITLE_SX, mb: 1.75 }}>Span waterfall</Box>
            <ExemplarWaterfall exemplar={rendered} />
            <Box sx={{ ...SECTION_TITLE_SX, mt: 3, mb: 1.25 }}>Exemplar attributes</Box>
            <AttributeList attributes={exemplarAttributesOf(rendered)} />
          </Box>
          <Box sx={{ px: 2.75, py: 2, flexShrink: 0, borderTop: 1, borderColor: 'divider' }}>
            <Box
              component="button"
              type="button"
              onClick={() => onOpenInTraces(rendered.traceId, rendered.spanId ?? null)}
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 1.1,
                width: '100%',
                height: 46,
                border: 'none',
                borderRadius: radii.sm,
                background: gradients.auroraAction,
                color: neutralColors.white,
                fontFamily: fontFamilies.display,
                fontSize: 14,
                fontWeight: 700,
                letterSpacing: '.3px',
                cursor: 'pointer',
                boxShadow: (t) => `0 8px 22px ${alpha(t.palette.primary.main, 0.32)}`,
                '&:hover': { filter: 'brightness(1.06)' },
                '&:focus-visible': { outline: (t) => `2px solid ${t.palette.primary.main}`, outlineOffset: 2 },
              }}
            >
              Open in Traces
              <ArrowForwardIcon sx={{ fontSize: 18 }} />
            </Box>
          </Box>
        </>
      ) : null}
    </Drawer>
  );
};

export default MetricExemplarDrawer;
