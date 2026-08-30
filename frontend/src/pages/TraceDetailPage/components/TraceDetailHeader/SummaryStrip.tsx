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
import { useState, type ReactNode } from 'react';
import { Box, Typography } from '@mui/material';
import { alpha } from '@mui/material/styles';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import {
  gradients,
  severity,
  tokenComposition,
} from '../../../../theme/colors';
import {
  formatDuration,
  formatTokens,
  formatUsd,
} from '../../../TracesPage/tracesApi';
import {
  cacheHitRateLabel,
  tokenShareLabel,
  type TokenBreakdown,
} from '../../../TracesPage/tokenBreakdown';
import PromptSummaryText from '../../../../components/PromptSummaryText';
import {
  loadOverviewCollapsed,
  persistOverviewCollapsed,
} from '../../summaryStripVisibility';

export interface SummaryItem {
  /** Uppercase label shown above the value. */
  label: string;
  /** When true, the value renders in the monospace stat style. */
  monospace?: boolean;
  /** Cost tile only: gradient-text treatment (matches Sessions' hero stats). */
  emphasis?: boolean;
  value: ReactNode;
  /** Full text surfaced as a tooltip only when the value is truncated. */
  title: string;
}

// One row of the "Time by operation" self-time breakdown — mirrors the
// Traces page's TraceSummaryInlineView.OpGroup so both pages share one shape.
export interface OpGroup {
  name: string;
  selfTimeMs: number;
  count: number;
  errorCount: number;
  other?: boolean;
}

// Same four kinds, same order, and the same hues the Tokens page uses — colors
// resolve through theme/colors.ts, never a literal here.
const TOKEN_SEGMENTS: Array<{
  key: keyof TokenBreakdown;
  label: string;
  color: string;
}> = [
  { key: 'cacheRead', label: 'Cache read', color: tokenComposition.cacheRead },
  { key: 'input', label: 'Input', color: tokenComposition.input },
  {
    key: 'cacheCreate',
    label: 'Cache creation',
    color: tokenComposition.cacheCreate,
  },
  { key: 'output', label: 'Output', color: tokenComposition.output },
];

// Label column of the token rows. Fixed so every bar starts on the same x and
// the swatch + label + rate tag never wraps.
const TOKEN_ROW_LABEL_WIDTH = 132;

// One labelled row in the composition list: color swatch + label (+ optional
// rate tag, cache-read only) + a log-scaled bar + raw value + share of the
// trace total. Replaces the old two-track + legend-grid layout with a single
// list, one row per nonzero token category.
const TokenRow = ({
  label,
  rateNote,
  color,
  widthPercent,
  barTitle,
  valueLabel,
  shareLabel,
}: {
  label: string;
  rateNote?: string;
  color: string;
  widthPercent: number;
  barTitle: string;
  valueLabel: string;
  shareLabel: string;
}) => (
  <Box
    sx={{
      display: 'grid',
      gridTemplateColumns: `${TOKEN_ROW_LABEL_WIDTH}px 1fr auto auto`,
      alignItems: 'center',
      gap: 1.5,
    }}
  >
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.85, minWidth: 0 }}>
      <Box
        sx={{
          width: 9,
          height: 9,
          borderRadius: radii.xs,
          bgcolor: color,
          flexShrink: 0,
        }}
      />
      <Typography
        sx={{
          typography: 'eyebrowSm',
          color: 'text.disabled',
          whiteSpace: 'nowrap',
        }}
      >
        {label}
        {rateNote ? (
          <Box
            component="span"
            sx={{
              typography: 'mono',
              fontSize: 9.5,
              letterSpacing: 0,
              textTransform: 'none',
              opacity: 0.8,
              ml: 0.4,
            }}
          >
            {rateNote}
          </Box>
        ) : null}
      </Typography>
    </Box>
    <Box
      title={barTitle}
      sx={{
        height: 8,
        borderRadius: radii.xs,
        overflow: 'hidden',
        bgcolor: 'action.hover',
      }}
    >
      <Box
        sx={{
          height: '100%',
          minWidth: 2,
          bgcolor: color,
          width: `${widthPercent}%`,
        }}
      />
    </Box>
    <Box
      component="span"
      sx={{
        typography: 'mono',
        fontSize: 11,
        color: 'text.secondary',
        whiteSpace: 'nowrap',
      }}
    >
      {valueLabel}
    </Box>
    <Box
      component="span"
      sx={{
        typography: 'mono',
        fontSize: 11,
        color: 'text.disabled',
        whiteSpace: 'nowrap',
        minWidth: 44,
        textAlign: 'right',
      }}
    >
      {shareLabel}
    </Box>
  </Box>
);

// Token composition: a stacked bar + 4-item legend (cache read / input / cache
// creation / output) with each segment's raw count, plus the model-call count
// and the trace's total cost. Sits in the left half of the two-column zone
// below the KPI strip, mirroring the Traces list's inline expand — the parent
// two-column Box supplies the divider and top border, not this card.
const TokenCompositionCard = ({
  tokenBreakdown,
  modelCallCount,
  totalCostUsd,
}: {
  tokenBreakdown: TokenBreakdown;
  modelCallCount: number;
  totalCostUsd: number;
}) => {
  if (tokenBreakdown.total <= 0) {
    // Spans and the api_request logs cost is derived from arrive over separate
    // OTLP endpoints, so a trace can have a positive backend-authoritative
    // cost before its spans (and their token attributes) have been ingested.
    // Claiming "no model calls" in that state directly contradicts the
    // non-zero Cost tile right above this card, so distinguish "genuinely no
    // model calls" from "tokens aren't in yet" and keep showing the cost.
    const hasCostWithoutTokens = totalCostUsd > 0;
    return (
      <Box sx={{ px: 2.25, py: 1.75 }}>
        <Typography
          sx={{ typography: 'mono', fontSize: 11.5, color: 'text.disabled' }}
        >
          {hasCostWithoutTokens
            ? "Token counts aren't available yet for this trace."
            : 'No model tokens — this trace made no model calls.'}
        </Typography>
        {hasCostWithoutTokens ? (
          <Typography
            sx={{
              typography: 'mono',
              fontSize: 11,
              color: 'text.disabled',
              mt: 0.75,
            }}
          >
            {formatUsd(totalCostUsd)}
          </Typography>
        ) : null}
      </Box>
    );
  }
  const modelCallLabel = modelCallCount
    ? `${modelCallCount} model ${modelCallCount === 1 ? 'call' : 'calls'}`
    : 'no model calls';
  const cacheHitLabel = cacheHitRateLabel(tokenBreakdown);
  // One list, log-scaled, not two tracks + a legend grid. Cache read routinely
  // runs 10-100x the other three counts, so a linear scale paints one solid bar
  // and the figures a reader is actually deciding on vanish into a hairline —
  // the same reasoning behind the Token Usage "over time" chart's log y-axis
  // (see frontend/CLAUDE.md's stacked-chart-labeling section). `maxValue` is
  // taken across all four categories, unfiltered, so a trace with only one
  // nonzero category still scales sensibly rather than filling the bar.
  const maxValue = Math.max(
    1,
    tokenBreakdown.cacheRead,
    tokenBreakdown.input,
    tokenBreakdown.cacheCreate,
    tokenBreakdown.output,
  );
  const logMax = Math.log10(maxValue + 1) || 1;
  const rows = TOKEN_SEGMENTS.filter(({ key }) => tokenBreakdown[key] > 0).sort(
    (a, b) => tokenBreakdown[b.key] - tokenBreakdown[a.key],
  );
  return (
    <Box sx={{ px: 2.25, py: 1.75 }}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'baseline',
          justifyContent: 'space-between',
          gap: 1.5,
          mb: 1.25,
        }}
      >
        <Typography
          sx={{
            typography: 'eyebrowSm',
            color: 'text.disabled',
            display: 'flex',
            alignItems: 'center',
            gap: 1.25,
          }}
        >
          Token composition
          <Box
            component="b"
            sx={{
              fontFamily: fontFamilies.display,
              fontSize: 20,
              fontWeight: 700,
              color: 'text.primary',
              letterSpacing: '-0.3px',
              textTransform: 'none',
            }}
          >
            {formatTokens(tokenBreakdown.total)}
          </Box>
          {cacheHitLabel !== null ? (
            <Box
              component="span"
              title="Cache read ÷ (cache read + input + cache creation)"
              sx={{
                typography: 'mono',
                fontSize: 12,
                fontWeight: 600,
                letterSpacing: '0.25px',
                textTransform: 'none',
                whiteSpace: 'nowrap',
                color: 'primary.main',
                bgcolor: alpha(tokenComposition.cacheRead, 0.14),
                borderRadius: radii.xs,
                px: 1,
                py: 0.5,
              }}
            >
              {cacheHitLabel} cached
            </Box>
          ) : null}
        </Typography>
        <Typography
          sx={{
            typography: 'mono',
            fontSize: 11,
            color: 'text.disabled',
            whiteSpace: 'nowrap',
          }}
        >
          {`${modelCallLabel} · ${formatUsd(totalCostUsd)}`}
        </Typography>
      </Box>
      <Box sx={{ display: 'grid', gap: 0.875, mb: 1.25 }}>
        {rows.map(({ key, label, color }) => {
          const value = tokenBreakdown[key];
          const widthPercent = Math.max(
            4,
            (Math.log10(value + 1) / logMax) * 100,
          );
          return (
            <TokenRow
              key={key}
              label={label}
              rateNote={key === 'cacheRead' ? '0.1×' : undefined}
              color={color}
              widthPercent={widthPercent}
              barTitle={`${label} — ${formatTokens(value)} tokens, ${tokenShareLabel(
                value,
                tokenBreakdown.total,
              )} of the trace`}
              valueLabel={formatTokens(value)}
              shareLabel={tokenShareLabel(value, tokenBreakdown.total)}
            />
          );
        })}
      </Box>
      <Typography
        sx={{ typography: 'mono', fontSize: 10.5, color: 'text.disabled' }}
      >
        Bars scaled logarithmically — cache read runs 10–100× the other
        categories, at a tenth of their rate.
      </Typography>
    </Box>
  );
};

// Self-time by operation, grouped by exact span name (no folding) — same rule
// the Traces list's inline expand uses. Sits in the right half of the
// two-column zone; `serviceHue` is the root span's service color (single hue
// for every non-error/non-"other" row, matching TraceSummaryInlineView) so a
// row's color says "errored" or "rolled up", not "this operation's category".
const OpBreakdownCard = ({
  shownOperations,
  opCount,
  totalMs,
  serviceHue,
}: {
  shownOperations: OpGroup[];
  opCount: number;
  totalMs: number;
  serviceHue: string;
}) => (
  <Box sx={{ px: 2.25, py: 1.75 }}>
    <Box
      sx={{
        display: 'flex',
        alignItems: 'baseline',
        justifyContent: 'space-between',
        gap: 1.5,
        mb: 1.25,
      }}
    >
      <Typography
        sx={{
          typography: 'eyebrowSm',
          color: 'text.disabled',
          display: 'flex',
          alignItems: 'center',
          gap: 1.25,
        }}
      >
        Time by operation
        <Box
          component="b"
          sx={{
            fontFamily: fontFamilies.display,
            fontSize: 20,
            fontWeight: 700,
            color: 'text.primary',
            letterSpacing: '-0.3px',
            textTransform: 'none',
          }}
        >
          {formatDuration(totalMs * 1e6)}
        </Box>
      </Typography>
      <Typography
        sx={{
          typography: 'mono',
          fontSize: 11,
          color: 'text.disabled',
          whiteSpace: 'nowrap',
        }}
      >
        {`${opCount} ops · self-time`}
      </Typography>
    </Box>
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.85 }}>
      {shownOperations.map((g) => {
        const pct = totalMs > 0 ? (g.selfTimeMs / totalMs) * 100 : 0;
        const dotColor = g.errorCount
          ? severity.error
          : g.other
            ? 'text.disabled'
            : serviceHue;
        return (
          <Box
            key={g.name}
            sx={{
              display: 'grid',
              gridTemplateColumns: 'minmax(0,1.1fr) 1fr auto',
              alignItems: 'center',
              gap: 1,
            }}
          >
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: 0.75,
                minWidth: 0,
              }}
            >
              <Box
                sx={{
                  width: 7,
                  height: 7,
                  borderRadius: '50%',
                  bgcolor: dotColor,
                  flexShrink: 0,
                }}
              />
              <Box
                component="span"
                title={g.name}
                sx={{
                  typography: 'mono',
                  fontSize: 11,
                  color: 'text.disabled',
                  fontWeight: 700,
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
              >
                {g.name}
              </Box>
              {g.errorCount ? (
                <Box
                  component="span"
                  sx={{
                    typography: 'mono',
                    flexShrink: 0,
                    px: 0.6,
                    borderRadius: radii.xs,
                    bgcolor: (th) => alpha(th.palette.error.main, 0.14),
                    color: 'error.main',
                    fontSize: 9.5,
                  }}
                >
                  {g.errorCount} err
                </Box>
              ) : null}
            </Box>
            <Box
              sx={{
                height: 7,
                borderRadius: '4px',
                bgcolor: 'action.hover',
                overflow: 'hidden',
              }}
            >
              <Box
                sx={{
                  width: `${Math.max(2, pct)}%`,
                  height: '100%',
                  borderRadius: '4px',
                  background: g.errorCount
                    ? `linear-gradient(90deg, ${alpha(severity.error, 0.55)}, ${severity.error})`
                    : `linear-gradient(90deg, ${alpha(serviceHue, 0.42)}, ${serviceHue})`,
                }}
              />
            </Box>
            <Box
              sx={{
                typography: 'mono',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.85,
                justifyContent: 'flex-end',
                fontSize: 11,
                color: 'text.secondary',
              }}
            >
              <Box component="span" sx={{ color: 'text.disabled' }}>
                ×{g.count}
              </Box>
              <Box component="span" sx={{ minWidth: 26, textAlign: 'right' }}>
                {pct.toFixed(0)}%
              </Box>
              <Box
                component="span"
                sx={{ minWidth: 48, textAlign: 'right', color: 'text.primary' }}
              >
                {formatDuration(g.selfTimeMs * 1e6)}
              </Box>
            </Box>
          </Box>
        );
      })}
    </Box>
  </Box>
);

const MetaLabel = ({ children }: { children: ReactNode }) => (
  <Box
    component="span"
    sx={{ typography: 'eyebrowSm', color: 'text.disabled', flexShrink: 0 }}
  >
    {children}
  </Box>
);

const metaRowSx = {
  display: 'flex',
  alignItems: 'center',
  gap: 1,
  fontSize: 12,
  minWidth: 0,
} as const;

// De-emphasized footer for reference info that isn't a glanceable KPI: root
// span, services, and start time. The trace/session ids live in the header's
// IdChips now (TraceDetailHeaderView), not here — spread with
// justifyContent: 'space-between' rather than left-clustered, since three
// short items left a large dead gap on wide viewports.
const MetaFooter = ({
  rootName,
  rootColor,
  serviceLabels,
  startedAtLabel,
}: {
  rootName: string;
  rootColor: string;
  serviceLabels: string[];
  startedAtLabel: string;
}) => (
  <Box
    sx={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      px: 2.25,
      py: 1.4,
      borderTop: 1,
      borderColor: 'divider',
      flexWrap: 'wrap',
      gap: 1.5,
    }}
  >
    <Box sx={metaRowSx}>
      <MetaLabel>Root span</MetaLabel>
      <Box
        sx={{
          width: 8,
          height: 8,
          borderRadius: radii.xs,
          bgcolor: rootColor,
          flexShrink: 0,
        }}
      />
      <Box
        component="span"
        sx={{
          typography: 'mono',
          color: 'text.primary',
          fontWeight: 600,
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {rootName}
      </Box>
    </Box>
    <Box sx={metaRowSx}>
      <MetaLabel>Services</MetaLabel>
      <Box
        component="span"
        sx={{ typography: 'mono', color: 'text.primary', fontWeight: 600 }}
      >
        {serviceLabels.length}{' '}
        <Box
          component="small"
          sx={{
            fontFamily: fontFamilies.body,
            fontSize: 12,
            fontWeight: 500,
            color: 'text.secondary',
          }}
        >
          {serviceLabels.join(' · ')}
        </Box>
      </Box>
    </Box>
    <Box sx={metaRowSx}>
      <MetaLabel>Started</MetaLabel>
      <Box
        component="span"
        sx={{ typography: 'mono', color: 'text.primary', fontWeight: 600 }}
      >
        {startedAtLabel}
      </Box>
    </Box>
  </Box>
);

export interface SummaryStripProps {
  items: SummaryItem[];
  prompt?: string | null;
  tokenBreakdown: TokenBreakdown;
  modelCallCount: number;
  totalCostUsd: number;
  // Time-by-operation self-time breakdown — see OpBreakdownCard.
  shownOperations: OpGroup[];
  opCount: number;
  totalMs: number;
  rootName: string;
  rootColor: string;
  serviceLabels: string[];
  startedAtLabel: string;
  durationLabel: string;
  spanCount: number;
  toolCallCount: number;
  errorCount: number;
}

// The trace's "Overview": a collapsible card holding, in order, the optional
// first-prompt row (hairline-divided, hidden when there's no prompt), the KPI
// tile row, a two-column zone (Token composition | Time by operation), and the
// de-emphasized meta footer. Each tile value is ellipsis-truncated and only
// shows a tooltip when it overflows. Collapsing hides everything but the
// header — a one-line caption stands in and the recovered vertical space goes
// to the span waterfall below. The collapsed state is a display preference
// persisted to localStorage (summaryStripVisibility.ts, same idiom as
// chipVisibility.ts), so it survives navigating between traces and reloads.
const SummaryStrip = ({
  items,
  prompt,
  tokenBreakdown,
  modelCallCount,
  totalCostUsd,
  shownOperations,
  opCount,
  totalMs,
  rootName,
  rootColor,
  serviceLabels,
  startedAtLabel,
  durationLabel,
  spanCount,
  toolCallCount,
  errorCount,
}: SummaryStripProps) => {
  const [collapsed, setCollapsed] = useState(loadOverviewCollapsed);

  const toggleCollapsed = () => {
    setCollapsed((previous) => {
      const next = !previous;
      persistOverviewCollapsed(next);
      return next;
    });
  };

  return (
    <Box
      sx={{
        mt: 2.25,
        mb: 2,
        border: 1,
        borderColor: 'divider',
        borderRadius: radii.xl,
        overflow: 'hidden',
        bgcolor: 'background.paper',
      }}
    >
      <Box
        onClick={toggleCollapsed}
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 1.5,
          px: 2.25,
          py: 1.5,
          cursor: 'pointer',
          userSelect: 'none',
          borderBottom: collapsed ? 0 : 1,
          borderColor: 'divider',
        }}
      >
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1.125,
            typography: 'eyebrowSm',
            color: 'text.secondary',
            fontSize: 12,
          }}
        >
          <ExpandMoreIcon
            sx={{
              fontSize: 17,
              color: 'text.disabled',
              transform: collapsed ? 'rotate(-90deg)' : 'none',
              transition: 'transform .15s',
            }}
          />
          Overview
        </Box>
        {collapsed ? (
          <Typography
            sx={{
              typography: 'mono',
              fontSize: 11,
              color: 'text.disabled',
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
            <Box component="b" sx={{ color: 'text.primary', fontWeight: 700 }}>
              {formatUsd(totalCostUsd)}
            </Box>
            {` · ${durationLabel} · ${spanCount} spans · ${toolCallCount} tool calls · ${formatTokens(tokenBreakdown.total)} tokens${
              errorCount
                ? ` · ${errorCount} error${errorCount === 1 ? '' : 's'}`
                : ''
            }`}
          </Typography>
        ) : null}
      </Box>

      {collapsed ? null : (
        <>
          {prompt ? (
            <Box
              onMouseEnter={(e) => {
                const promptElement = e.currentTarget.querySelector(
                  '[data-prompt-text]',
                ) as HTMLElement | null;
                if (
                  promptElement &&
                  promptElement.scrollWidth > promptElement.clientWidth
                ) {
                  e.currentTarget.setAttribute('title', prompt);
                } else {
                  e.currentTarget.removeAttribute('title');
                }
              }}
              sx={{
                display: 'flex',
                alignItems: 'baseline',
                gap: 1.5,
                px: 2.25,
                py: 1.4,
                borderBottom: 1,
                borderColor: 'divider',
              }}
            >
              <Typography
                sx={{
                  typography: 'eyebrowSm',
                  color: 'text.disabled',
                  flexShrink: 0,
                }}
              >
                Prompt
              </Typography>
              <Box
                data-prompt-text
                sx={{
                  fontSize: 13.5,
                  color: 'text.primary',
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  minWidth: 0,
                }}
              >
                <PromptSummaryText prompt={prompt} />
              </Box>
            </Box>
          ) : null}

          <Box sx={{ display: 'flex', alignItems: 'stretch' }}>
            {items.map((item, i) => (
              <Box
                key={item.label}
                sx={{
                  flex: 1,
                  minWidth: 0,
                  px: 2.25,
                  py: 1.6,
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 0.5,
                  borderRight: i < items.length - 1 ? 1 : 0,
                  borderColor: 'divider',
                }}
              >
                <Typography
                  sx={{
                    typography: 'eyebrowSm',
                    color: 'text.disabled',
                  }}
                >
                  {item.label}
                </Typography>
                <Box
                  onMouseEnter={(e) => {
                    const element = e.currentTarget;
                    if (element.scrollWidth > element.clientWidth) {
                      element.setAttribute('title', item.title);
                    } else {
                      element.removeAttribute('title');
                    }
                  }}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1,
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    color: 'text.primary',
                    ...(item.emphasis
                      ? {
                          fontFamily: fontFamilies.display,
                          fontSize: 22,
                          fontWeight: 700,
                          background: gradients.auroraActionSoft,
                          backgroundClip: 'text',
                          WebkitBackgroundClip: 'text',
                          color: 'transparent',
                        }
                      : item.monospace
                        ? {
                            typography: 'mono',
                            fontSize: 16,
                            fontWeight: 600,
                          }
                        : {
                            fontFamily: fontFamilies.display,
                            fontSize: 20,
                            fontWeight: 700,
                          }),
                  }}
                >
                  {item.value}
                </Box>
              </Box>
            ))}
          </Box>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
              borderTop: 1,
              borderColor: 'divider',
            }}
          >
            <Box sx={{ borderRight: 1, borderColor: 'divider' }}>
              <TokenCompositionCard
                tokenBreakdown={tokenBreakdown}
                modelCallCount={modelCallCount}
                totalCostUsd={totalCostUsd}
              />
            </Box>
            <OpBreakdownCard
              shownOperations={shownOperations}
              opCount={opCount}
              totalMs={totalMs}
              serviceHue={rootColor}
            />
          </Box>
          <MetaFooter
            rootName={rootName}
            rootColor={rootColor}
            serviceLabels={serviceLabels}
            startedAtLabel={startedAtLabel}
          />
        </>
      )}
    </Box>
  );
};

export default SummaryStrip;
