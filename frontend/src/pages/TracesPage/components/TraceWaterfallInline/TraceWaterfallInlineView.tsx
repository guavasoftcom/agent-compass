import { Box, CircularProgress, Typography } from '@mui/material';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import type { SpanRow, TraceRow } from '../../../../api';
import { formatDuration, serviceOf } from '../../tracesApi';
import { serviceColor } from '../traceColors';

export interface PlacedSpan {
  span: SpanRow;
  offPct: number;
  wPct: number;
  depth: number;
  error: boolean;
}

export interface TraceWaterfallInlineViewProps {
  trace: TraceRow;
  placed: PlacedSpan[];
  totalMs: number;
  isLoading: boolean;
  onOpenTrace: () => void;
}

const TraceWaterfallInlineView = ({
  trace,
  placed,
  totalMs,
  isLoading,
  onOpenTrace,
}: TraceWaterfallInlineViewProps) => {
  return (
    <Box
      sx={{
        px: 2,
        py: 1.75,
        bgcolor: (t) =>
          t.palette.mode === 'dark'
            ? 'rgba(255,255,255,0.03)'
            : 'rgba(28,24,48,0.025)',
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          mb: 1.1,
        }}
      >
        <Typography
          sx={{
            fontFamily: "'Sora', sans-serif",
            fontSize: 10.5,
            fontWeight: 700,
            letterSpacing: '1px',
            textTransform: 'uppercase',
            color: 'text.secondary',
          }}
        >
          Span waterfall
          <Box
            component="span"
            sx={{
              ml: 1,
              px: 0.9,
              py: 0.15,
              borderRadius: 0.75,
              bgcolor: 'action.hover',
              fontFamily: "'JetBrains Mono', monospace",
              fontSize: 10.5,
              fontWeight: 500,
              color: 'text.disabled',
            }}
          >
            {trace.spanCount} spans
          </Box>
        </Typography>
        <Box
          component="button"
          onClick={(e) => {
            e.stopPropagation();
            onOpenTrace();
          }}
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 0.75,
            height: 30,
            px: 1.5,
            border: 'none',
            borderRadius: 1.1,
            cursor: 'pointer',
            color: 'common.white',
            background: 'linear-gradient(135deg, #8b5cff, #ff6ad5)',
            boxShadow: '0 6px 16px rgba(139,92,255,0.36)',
            fontFamily: "'Sora', sans-serif",
            fontSize: 12.5,
            fontWeight: 600,
          }}
        >
          Open full trace <ArrowForwardIcon sx={{ fontSize: 15 }} />
        </Box>
      </Box>

      {isLoading ? (
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
          <Typography sx={{ fontSize: 12.5 }}>Loading spans…</Typography>
        </Box>
      ) : (
        <Box
          sx={{
            border: 1,
            borderColor: 'divider',
            borderRadius: 1.5,
            overflow: 'hidden',
            bgcolor: 'background.paper',
          }}
        >
          {placed.map((p) => {
            const labelLeft = p.offPct < 60;
            const hue = p.error
              ? '#e5484d'
              : serviceColor(serviceOf(p.span.name));
            return (
              <Box
                key={p.span.spanId}
                sx={{
                  display: 'grid',
                  gridTemplateColumns: '220px 1fr',
                  alignItems: 'center',
                  height: 25,
                  borderBottom: 1,
                  borderColor: 'divider',
                  '&:last-of-type': { borderBottom: 'none' },
                  '&:hover': { bgcolor: 'action.hover' },
                }}
              >
                <Box
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 0.75,
                    minWidth: 0,
                    pl: `${13 + p.depth * 14}px`,
                  }}
                >
                  <Box
                    sx={{
                      width: 6,
                      height: 6,
                      borderRadius: '50%',
                      bgcolor: hue,
                      flexShrink: 0,
                    }}
                  />
                  <Box
                    component="span"
                    sx={{
                      fontFamily: "'JetBrains Mono', monospace",
                      fontSize: 11.5,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                    }}
                    title={p.span.name}
                  >
                    {p.span.name}
                  </Box>
                  {p.span.kind ? (
                    <Box
                      component="span"
                      sx={{
                        fontFamily: "'JetBrains Mono', monospace",
                        fontSize: 9.5,
                        color: 'text.disabled',
                        flexShrink: 0,
                      }}
                    >
                      {p.span.kind}
                    </Box>
                  ) : null}
                </Box>
                <Box sx={{ position: 'relative', height: '100%', mx: 1.5 }}>
                  <Box
                    sx={{
                      position: 'absolute',
                      top: '50%',
                      transform: 'translateY(-50%)',
                      height: 13,
                      borderRadius: 1,
                      bgcolor: hue,
                      opacity: 0.82,
                      left: `${p.offPct}%`,
                      width: `${p.wPct}%`,
                      minWidth: 3,
                    }}
                  />
                  <Box
                    component="span"
                    sx={{
                      position: 'absolute',
                      top: '50%',
                      transform: 'translateY(-50%)',
                      fontFamily: "'JetBrains Mono', monospace",
                      fontSize: 10,
                      color: 'text.secondary',
                      whiteSpace: 'nowrap',
                      ...(labelLeft
                        ? { left: `calc(${p.offPct + p.wPct}% + 6px)` }
                        : { right: `calc(${100 - p.offPct}% + 6px)` }),
                    }}
                  >
                    {formatDuration(p.span.durationNanos)}
                  </Box>
                </Box>
              </Box>
            );
          })}
          <Box
            sx={{
              px: 1.75,
              py: 0.75,
              display: 'flex',
              gap: 2.5,
              borderTop: 1,
              borderColor: 'divider',
              fontFamily: "'JetBrains Mono', monospace",
              fontSize: 11,
              color: 'text.disabled',
            }}
          >
            <span>total {formatDuration(totalMs * 1e6)}</span>
            <span>trace {trace.traceId.slice(0, 18)}…</span>
            {trace.sessionId ? <span>session {trace.sessionId}</span> : null}
          </Box>
        </Box>
      )}
    </Box>
  );
};

export default TraceWaterfallInlineView;
