import type { ReactNode } from 'react';
import { alpha, Box, CircularProgress, Typography } from '@mui/material';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import {
  auroraColors,
  gradients,
  neutralColors,
  severity,
  tokenComposition,
} from '../../../../theme/colors';
import type { TraceRow } from '../../../../api';
import { formatDuration, formatTokens } from '../../tracesApi';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';

export interface OpGroup {
  name: string;
  selfTimeMs: number;
  count: number;
  errorCount: number;
  other?: boolean;
}

export interface TokenComposition {
  input: number;
  output: number;
  cacheRead: number;
  cacheCreate: number;
  total: number;
}

export interface TraceSummaryModel {
  totalMs: number;
  shownOperations: OpGroup[];
  opCount: number;
  tokenTotals: TokenComposition;
  calls: number;
  maxDepth: number;
}

export interface TraceSummaryInlineViewProps {
  trace: TraceRow;
  model: TraceSummaryModel | null;
  isLoading: boolean;
  serviceHue: string;
  callLabel: string;
  onOpenTrace: () => void;
}

const TOK_SEG: ReadonlyArray<
  [string, 'cacheRead' | 'input' | 'cacheCreate' | 'output', string]
> = [
  ['Cache read', 'cacheRead', tokenComposition.cacheRead],
  ['Input', 'input', tokenComposition.input],
  ['Cache creation', 'cacheCreate', tokenComposition.cacheCreate],
  ['Output', 'output', tokenComposition.output],
];

// Inline span summary — a trace has no attributes of its own, so instead of
// duplicating the detail page's waterfall we surface where the trace spent its
// time: glanceable KPIs, token composition, and honest self-time grouped by
// operation. Mirrors the mockup's buildDetail().
const TraceSummaryInlineView = ({
  trace,
  model,
  isLoading,
  serviceHue,
  callLabel,
  onOpenTrace,
}: TraceSummaryInlineViewProps) => {
  return (
    <Box
      sx={{
        px: 2,
        py: 1.75,
        bgcolor: (th) =>
          th.palette.mode === 'dark'
            ? alpha(neutralColors.white, 0.03)
            : alpha(neutralColors.inkLight, 0.025),
      }}
      onClick={(e) => e.stopPropagation()}
    >
      {isLoading || !model ? (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            py: 2,
            color: 'text.secondary',
          }}
        >
          <CircularProgress size={14} thickness={5} />
          <Typography sx={{ fontSize: 12.5 }}>Loading span summary…</Typography>
        </Box>
      ) : (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          {/* KPI strip — glanceable metrics, full width */}
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: 'repeat(2, 1fr)',
                sm: 'repeat(3, 1fr)',
                md: 'repeat(5, 1fr)',
              },
              gap: 1,
            }}
          >
            <Tile label="Spans" value={String(trace.spanCount)} />
            <Tile
              label="Duration"
              value={formatDuration(model.totalMs * 1e6)}
            />
            <Tile
              label="Tokens"
              value={formatTokens(model.tokenTotals.total)}
              cap={callLabel}
            />
            <Tile label="Depth" value={`${model.maxDepth}`} unit="levels" />
            <Tile
              label="Errors"
              value={String(trace.errorCount)}
              bad={trace.errorCount > 0}
            />
          </Box>

          {/* Two-column zone */}
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', md: '1fr 1.5fr' },
              gap: 2.5,
              alignItems: 'start',
            }}
          >
            {/* Token composition */}
            <Box>
              <SectionHead>Token composition</SectionHead>
              {model.tokenTotals.total > 0 ? (
                <Box>
                  <Box
                    sx={{
                      display: 'flex',
                      alignItems: 'baseline',
                      justifyContent: 'space-between',
                      mb: 0.75,
                    }}
                  >
                    <Box
                      component="span"
                      sx={{
                        typography: 'mono',
                        fontSize: 13,
                        fontWeight: 600,
                        color: 'text.primary',
                      }}
                    >
                      {formatTokens(model.tokenTotals.total)}{' '}
                      <Box
                        component="span"
                        sx={{
                          fontSize: 10.5,
                          color: 'text.disabled',
                          fontWeight: 400,
                        }}
                      >
                        tokens
                      </Box>
                    </Box>
                    <Box
                      component="span"
                      sx={{
                        typography: 'mono',
                        fontSize: 10.5,
                        color: 'text.secondary',
                      }}
                    >
                      {callLabel}
                    </Box>
                  </Box>
                  <Box
                    sx={{
                      display: 'flex',
                      height: 9,
                      borderRadius: '4px',
                      overflow: 'hidden',
                      bgcolor: 'action.hover',
                      mb: 0.85,
                    }}
                  >
                    {TOK_SEG.map(([k, key, c]) =>
                      model.tokenTotals[key] > 0 ? (
                        <Box
                          key={k}
                          sx={{
                            width: `${(model.tokenTotals[key] / model.tokenTotals.total) * 100}%`,
                            bgcolor: c,
                          }}
                        />
                      ) : null,
                    )}
                  </Box>
                  <Box
                    sx={{
                      display: 'grid',
                      gridTemplateColumns: 'repeat(2, 1fr)',
                      gap: '4px 14px',
                    }}
                  >
                    {TOK_SEG.map(([k, key, c]) => (
                      <Box
                        key={k}
                        sx={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 0.75,
                          minWidth: 0,
                        }}
                      >
                        <Box
                          sx={{
                            width: 8,
                            height: 8,
                            borderRadius: '2px',
                            bgcolor: c,
                            flexShrink: 0,
                          }}
                        />
                        <Box
                          component="span"
                          sx={{
                            fontSize: 10.5,
                            color: 'text.secondary',
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                          }}
                        >
                          {k}
                        </Box>
                        <Box
                          component="span"
                          sx={{
                            typography: 'mono',
                            ml: 'auto',
                            fontSize: 10.5,
                            color: 'text.primary',
                          }}
                        >
                          {formatTokens(model.tokenTotals[key])}
                        </Box>
                      </Box>
                    ))}
                  </Box>
                </Box>
              ) : (
                <Box sx={{ fontSize: 11.5, color: 'text.disabled' }}>
                  No model tokens — this trace made no model calls.
                </Box>
              )}
            </Box>

            {/* Time by operation */}
            <Box>
              <SectionHead>
                Time by operation
                <Pill>{model.opCount} ops · self-time</Pill>
              </SectionHead>
              <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.85 }}>
                {model.shownOperations.map((g) => {
                  const pct =
                    model.totalMs > 0
                      ? (g.selfTimeMs / model.totalMs) * 100
                      : 0;
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
                          sx={{
                            typography: 'mono',
                            fontSize: 11.5,
                            color: 'text.primary',
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                          }}
                          title={g.name}
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
                        <Box
                          component="span"
                          sx={{ minWidth: 26, textAlign: 'right' }}
                        >
                          {pct.toFixed(0)}%
                        </Box>
                        <Box
                          component="span"
                          sx={{
                            minWidth: 48,
                            textAlign: 'right',
                            color: 'text.primary',
                          }}
                        >
                          {formatDuration(g.selfTimeMs * 1e6)}
                        </Box>
                      </Box>
                    </Box>
                  );
                })}
              </Box>
            </Box>
          </Box>

          {/* Meta footer — full width */}
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 2,
              flexWrap: 'wrap',
              pt: 1.5,
              borderTop: 1,
              borderColor: 'divider',
            }}
          >
            <Meta k="Trace ID" v={`${trace.traceId.slice(0, 18)}…`} />
            {trace.sessionId ? <Meta k="Session" v={trace.sessionId} /> : null}
            <Box
              component="button"
              onClick={(e) => {
                e.stopPropagation();
                onOpenTrace();
              }}
              sx={{
                ml: 'auto',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.75,
                height: 30,
                px: 1.5,
                border: 'none',
                borderRadius: radii.sm,
                cursor: 'pointer',
                color: 'common.white',
                background: gradients.auroraAction,
                boxShadow: `0 6px 16px ${alpha(auroraColors.violetLight, 0.36)}`,
                fontFamily: fontFamilies.display,
                fontSize: 12.5,
                fontWeight: 600,
              }}
            >
              Open full trace <ArrowForwardIcon sx={{ fontSize: 15 }} />
            </Box>
          </Box>
        </Box>
      )}
    </Box>
  );
};

const SectionHead = ({ children }: { children: ReactNode }) => (
  <Typography
    sx={{
      typography: 'eyebrowSm',
      display: 'flex',
      alignItems: 'center',
      gap: 1,
      mb: 1.25,
      color: 'text.secondary',
    }}
  >
    {children}
  </Typography>
);

const Pill = ({ children }: { children: ReactNode }) => (
  <Box
    component="span"
    sx={{
      typography: 'mono',
      px: 0.9,
      py: 0.15,
      borderRadius: radii.xs,
      bgcolor: 'action.hover',
      fontSize: 10,
      fontWeight: 500,
      letterSpacing: 0,
      textTransform: 'none',
      color: 'text.disabled',
    }}
  >
    {children}
  </Box>
);

const Tile = ({
  label,
  value,
  cap,
  unit,
  bad,
}: {
  label: string;
  value: string;
  cap?: string;
  unit?: string;
  bad?: boolean;
}) => (
  <Box
    sx={{
      display: 'flex',
      flexDirection: 'column',
      gap: 0.2,
      p: 1,
      borderRadius: radii.sm,
      border: 1,
      borderColor: 'divider',
      bgcolor: 'background.paper',
    }}
  >
    <Box
      component="span"
      sx={{
        fontSize: 9.5,
        fontWeight: 700,
        letterSpacing: '0.5px',
        textTransform: 'uppercase',
        color: 'text.disabled',
      }}
    >
      {label}
    </Box>
    <Box
      component="span"
      sx={{
        typography: 'mono',
        fontSize: 16,
        fontWeight: 600,
        color: bad ? 'error.main' : 'text.primary',
      }}
    >
      {value}
      {unit ? (
        <Box
          component="span"
          sx={{
            fontSize: 10,
            color: 'text.disabled',
            fontWeight: 400,
            ml: 0.4,
          }}
        >
          {unit}
        </Box>
      ) : null}
    </Box>
    {cap ? (
      <Box
        component="span"
        sx={{
          fontSize: 9.5,
          color: 'text.disabled',
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {cap}
      </Box>
    ) : null}
  </Box>
);

const Meta = ({ k, v }: { k: string; v: string }) => (
  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.1 }}>
    <Box
      component="span"
      sx={{
        fontSize: 9.5,
        fontWeight: 700,
        letterSpacing: '0.5px',
        textTransform: 'uppercase',
        color: 'text.disabled',
      }}
    >
      {k}
    </Box>
    <Box
      component="span"
      sx={{
        typography: 'mono',
        fontSize: 11,
        color: 'text.secondary',
      }}
    >
      {v}
    </Box>
  </Box>
);

export default TraceSummaryInlineView;
