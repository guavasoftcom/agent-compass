import { useState, type ReactNode } from 'react';
import { Box, Typography } from '@mui/material';
import { alpha } from '@mui/material/styles';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import { gradients, tokenComposition } from '../../../../theme/colors';
import { formatTokens, formatUsd } from '../../../TracesPage/tracesApi';
import {
  cacheHitRateLabel,
  fullRateTokens,
  tokenShareLabel,
  type TokenBreakdown,
} from '../../../TracesPage/tokenBreakdown';

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

const COLLAPSE_STORAGE_KEY = 'trace-detail-overview-collapsed';

// Label column of the two composition tracks. Fixed so both bars start on the
// same x and can be read against each other.
const TRACK_LABEL_WIDTH = 118;

// One labelled bar: eyebrow label (with an optional rate note), the track, and
// the figure it sums to.
const TokenTrack = ({
  label,
  rateNote,
  barTitle,
  valueLabel,
  children,
}: {
  label: string;
  rateNote?: string;
  barTitle: string;
  valueLabel: string;
  children: ReactNode;
}) => (
  <Box
    sx={{
      display: 'grid',
      gridTemplateColumns: `${TRACK_LABEL_WIDTH}px 1fr auto`,
      alignItems: 'center',
      gap: 1.5,
    }}
  >
    <Typography
      sx={{ typography: 'eyebrowSm', color: 'text.disabled', whiteSpace: 'nowrap' }}
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
    <Box
      title={barTitle}
      sx={{
        display: 'flex',
        height: 8,
        borderRadius: radii.xs,
        overflow: 'hidden',
        bgcolor: 'action.hover',
      }}
    >
      {children}
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
  </Box>
);

// Same read-once / write-guarded shape theme/colorMode.tsx uses for its own
// persisted preference — the initial value is read lazily in useState rather
// than set from an effect (which is a react-hooks/set-state-in-effect error).
const readInitialCollapsed = (): boolean => {
  if (typeof window === 'undefined') {
    return false;
  }
  return window.localStorage?.getItem(COLLAPSE_STORAGE_KEY) === '1';
};

const persistCollapsed = (collapsed: boolean): void => {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage?.setItem(COLLAPSE_STORAGE_KEY, collapsed ? '1' : '0');
  } catch {
    // ignore quota / disabled storage
  }
};

// Token composition: a stacked bar + 4-item legend (cache read / input / cache
// creation / output) with each segment's raw count, plus the model-call count
// and the trace's total cost. Replaces the old single "Tokens" KPI tile with
// something that shows where the tokens actually went.
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
      <Box sx={{ px: 2.25, py: 1.75, borderTop: 1, borderColor: 'divider' }}>
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
  // Two tracks, not one. Cache read routinely runs 100-1000x the other counts, so
  // a single stacked bar paints solid violet and the three numbers a reader is
  // actually deciding on vanish into a hairline. The split is by rate, not by
  // billed/free — cache read is billed too, at a tenth of the input rate — so the
  // top track scales to the full-rate subtotal and cache read keeps its own
  // track, measured against the trace total.
  const fullRate = fullRateTokens(tokenBreakdown);
  const cacheReadShare =
    tokenBreakdown.total > 0
      ? (tokenBreakdown.cacheRead / tokenBreakdown.total) * 100
      : 0;
  const fullRateSegments = TOKEN_SEGMENTS.filter(
    ({ key }) => key !== 'cacheRead' && tokenBreakdown[key] > 0,
  );
  return (
    <Box sx={{ px: 2.25, py: 1.75, borderTop: 1, borderColor: 'divider' }}>
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
      <Box sx={{ display: 'grid', gap: 1.125, mb: 1.625 }}>
        <TokenTrack
          label="Full rate"
          barTitle="Input, cache creation and output — the tokens priced at full rate, scaled to their subtotal"
          valueLabel={formatTokens(fullRate)}
        >
          {fullRateSegments.map(({ key, color }) => (
            <Box
              key={key}
              sx={{
                height: '100%',
                minWidth: 2,
                bgcolor: color,
                width: `${(tokenBreakdown[key] / Math.max(1, fullRate)) * 100}%`,
              }}
            />
          ))}
        </TokenTrack>
        <TokenTrack
          label="Cache read"
          rateNote="0.1×"
          barTitle="Cache read as a share of all tokens in this trace — billed at a tenth of the input rate"
          valueLabel={`${formatTokens(tokenBreakdown.cacheRead)} · ${tokenShareLabel(
            tokenBreakdown.cacheRead,
            tokenBreakdown.total,
          )}`}
        >
          {tokenBreakdown.cacheRead > 0 ? (
            <Box
              sx={{
                height: '100%',
                minWidth: 2,
                bgcolor: tokenComposition.cacheRead,
                opacity: 0.85,
                width: `${cacheReadShare}%`,
              }}
            />
          ) : null}
        </TokenTrack>
      </Box>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'repeat(4, 1fr)',
          gap: '8px 18px',
        }}
      >
        {TOKEN_SEGMENTS.map(({ key, label, color }) => (
          <Box
            key={key}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1,
              fontSize: 12,
              minWidth: 0,
            }}
          >
            <Box
              sx={{
                width: 9,
                height: 9,
                borderRadius: radii.xs,
                bgcolor: color,
                flexShrink: 0,
              }}
            />
            <Box
              component="span"
              sx={{
                flex: 1,
                color: 'text.secondary',
                fontFamily: fontFamilies.body,
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
              }}
            >
              {label}
            </Box>
            <Box
              component="span"
              sx={{
                typography: 'mono',
                color: 'text.primary',
                fontWeight: 600,
                flexShrink: 0,
              }}
            >
              {formatTokens(tokenBreakdown[key])}
            </Box>
            {/* Share of the trace total, so the legend answers "how much of this
                trace was cache read?" without the reader doing the division. */}
            <Box
              component="span"
              sx={{
                typography: 'mono',
                fontSize: 11,
                color: 'text.disabled',
                flexShrink: 0,
                minWidth: 44,
                textAlign: 'right',
              }}
            >
              {tokenShareLabel(tokenBreakdown[key], tokenBreakdown.total)}
            </Box>
          </Box>
        ))}
      </Box>
    </Box>
  );
};

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

// De-emphasized footer for reference info that isn't a glanceable KPI: the full
// trace/session ids (plain text — the Aurora sync dropped the breadcrumb's
// copy-to-clipboard IdChip), root span, services, and start time.
const MetaFooter = ({
  traceId,
  sessionId,
  rootName,
  rootColor,
  serviceLabels,
  startedAtLabel,
}: {
  traceId: string;
  sessionId: string | null;
  rootName: string;
  rootColor: string;
  serviceLabels: string[];
  startedAtLabel: string;
}) => (
  <Box
    sx={{
      display: 'flex',
      alignItems: 'center',
      gap: 3.25,
      px: 2.25,
      py: 1.4,
      borderTop: 1,
      borderColor: 'divider',
      flexWrap: 'wrap',
    }}
  >
    <Box sx={metaRowSx}>
      <MetaLabel>Trace ID</MetaLabel>
      <Box
        component="span"
        sx={{
          typography: 'mono',
          color: 'text.primary',
          fontWeight: 600,
          wordBreak: 'break-all',
        }}
      >
        {traceId}
      </Box>
    </Box>
    {sessionId ? (
      <Box sx={metaRowSx}>
        <MetaLabel>Session</MetaLabel>
        <Box
          component="span"
          sx={{
            typography: 'mono',
            color: 'text.primary',
            fontWeight: 600,
            wordBreak: 'break-all',
          }}
        >
          {sessionId}
        </Box>
      </Box>
    ) : null}
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
  traceId: string;
  sessionId: string | null;
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
// tile row, the Token composition card, and the de-emphasized meta footer. Each
// tile value is ellipsis-truncated and only shows a tooltip when it overflows.
// Collapsing hides everything but the header — a one-line caption stands in and
// the recovered vertical space goes to the span waterfall below — and the choice
// persists in localStorage so it survives a reload.
const SummaryStrip = ({
  items,
  prompt,
  tokenBreakdown,
  modelCallCount,
  totalCostUsd,
  traceId,
  sessionId,
  rootName,
  rootColor,
  serviceLabels,
  startedAtLabel,
  durationLabel,
  spanCount,
  toolCallCount,
  errorCount,
}: SummaryStripProps) => {
  const [collapsed, setCollapsed] = useState(readInitialCollapsed);

  const toggleCollapsed = () => {
    setCollapsed((previous) => {
      const next = !previous;
      persistCollapsed(next);
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
                {prompt}
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

          <TokenCompositionCard
            tokenBreakdown={tokenBreakdown}
            modelCallCount={modelCallCount}
            totalCostUsd={totalCostUsd}
          />
          <MetaFooter
            traceId={traceId}
            sessionId={sessionId}
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
