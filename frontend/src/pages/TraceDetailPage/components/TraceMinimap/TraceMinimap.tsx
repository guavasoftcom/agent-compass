import { useRef } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { Box, alpha, useTheme } from '@mui/material';
import type { SpanRow } from '../../../../api';
import {
  spanColor,
  SERVICE_LEGEND,
} from '../../../TracesPage/components/traceColors';
import { radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';

export interface ZoomView {
  s: number;
  e: number;
}

interface Props {
  spans: SpanRow[];
  earliestStartMs: number;
  totalMs: number;
  depthBySpanId: Map<string, number>;
  view: ZoomView;
  onViewChange: (view: ZoomView) => void;
}

type DragMode = 'move' | 'l' | 'r' | 'create';

const clamp = (value: number, min: number, max: number) =>
  Math.max(min, Math.min(max, value));

const TraceMinimap = ({
  spans,
  earliestStartMs,
  totalMs,
  depthBySpanId,
  view,
  onViewChange,
}: Props) => {
  const theme = useTheme();
  const minimapRef = useRef<HTMLDivElement>(null);
  const visibleSpanMs = Math.max(1, view.e - view.s);
  const isZoomed = view.s > 0 || view.e < totalMs;
  const offsetMsOf = (s: SpanRow) =>
    Date.parse(s.startTimestamp) - earliestStartMs;
  const durationMsOf = (s: SpanRow) => s.durationNanos / 1e6;

  const formatDuration = (ms: number) => {
    if (ms >= 1000) {
      return `${(ms / 1000).toFixed(2)}s`;
    }
    return `${ms.toFixed(0)}ms`;
  };

  // Drag the brush body to pan, either edge to resize, or bare track to create a new range.
  const startDrag =
    (mode: DragMode, anchor?: number) => (e: ReactMouseEvent) => {
      e.preventDefault();
      e.stopPropagation();
      const host = minimapRef.current;
      if (!host) {
        return;
      }
      const hostWidth = host.getBoundingClientRect().width;
      const startX = e.clientX;
      const startView = view.s;
      const endView = view.e;
      const minimumZoomMs = Math.max(1, totalMs * 0.02);

      const onMove = (m: MouseEvent) => {
        const deltaMs = ((m.clientX - startX) / hostWidth) * totalMs;
        let start = startView;
        let end = endView;

        if (mode === 'move') {
          start = startView + deltaMs;
          end = endView + deltaMs;
          if (start < 0) {
            end -= start;
            start = 0;
          }
          if (end > totalMs) {
            start -= end - totalMs;
            end = totalMs;
          }
        } else if (mode === 'l') {
          start = Math.max(
            0,
            Math.min(startView + deltaMs, endView - minimumZoomMs),
          );
        } else if (mode === 'r') {
          end = Math.min(
            totalMs,
            Math.max(endView + deltaMs, startView + minimumZoomMs),
          );
        } else if (mode === 'create' && anchor !== undefined) {
          const currentT = clamp(anchor + deltaMs, 0, totalMs);
          start = Math.min(anchor, currentT);
          end = Math.max(anchor, currentT);
          if (end - start < minimumZoomMs) {
            if (anchor < totalMs / 2) {
              end = Math.min(totalMs, start + minimumZoomMs);
            } else {
              start = Math.max(0, end - minimumZoomMs);
            }
          }
        }

        onViewChange({ s: start, e: end });
      };

      const onUp = () => {
        document.removeEventListener('mousemove', onMove);
        document.removeEventListener('mouseup', onUp);
      };

      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup', onUp);
    };

  const handleTrackMouseDown = (e: ReactMouseEvent<HTMLDivElement>) => {
    const host = minimapRef.current;
    if (!host) {
      return;
    }
    const rect = host.getBoundingClientRect();
    const t = clamp(
      ((e.clientX - rect.left) / rect.width) * totalMs,
      0,
      totalMs,
    );
    startDrag('create', t)(e);
  };

  const sortedSpans = [...spans].sort(
    (a, b) =>
      (a.statusCode === 'error' ? 1 : 0) - (b.statusCode === 'error' ? 1 : 0),
  );

  return (
    <Box
      sx={{
        px: 2,
        pt: 1.25,
        pb: 1.5,
        borderBottom: 1,
        borderColor: 'divider',
        flexShrink: 0,
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          gap: 1,
          mb: 1,
        }}
      >
        {/* Per-operation hue legend — explains the minimap span colors. Error
            spans override to red (see the toolbar legend above). */}
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            flexWrap: 'wrap',
            gap: 1.2,
          }}
        >
          {SERVICE_LEGEND.map(({ label, color }) => (
            <Box
              key={label}
              component="span"
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.6,
                fontSize: 9.5,
                color: 'text.disabled',
                fontFamily: fontFamilies.display,
                fontWeight: 600,
                letterSpacing: '0.3px',
                pointerEvents: 'none',
              }}
            >
              <Box
                sx={{
                  width: 8,
                  height: 8,
                  borderRadius: '2px',
                  bgcolor: color,
                }}
              />
              {label}
            </Box>
          ))}
        </Box>
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            fontSize: 9.5,
            color: 'text.disabled',
            fontFamily: fontFamilies.display,
            fontWeight: 600,
            letterSpacing: '0.3px',
            pointerEvents: 'none',
          }}
        >
          {isZoomed && (
            <Box
              component="span"
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.5,
                px: 0.75,
                py: 0.25,
                backgroundColor: 'action.hover',
                borderRadius: '4px',
                fontSize: 8.5,
              }}
            >
              <Box
                component="span"
                sx={{
                  fontFamily: fontFamilies.mono,
                  fontWeight: 700,
                  fontSize: 9,
                }}
              >
                {formatDuration(view.s)}–{formatDuration(view.e)}
              </Box>
              <Box
                component="span"
                sx={{
                  fontSize: 7.5,
                  textTransform: 'uppercase',
                  fontWeight: 500,
                  color: 'text.secondary',
                  letterSpacing: '0.2px',
                }}
              >
                of {formatDuration(totalMs)}
              </Box>
            </Box>
          )}
          <Box component="span">
            {isZoomed
              ? 'dbl-click resets'
              : 'drag to select · drag edges to resize'}
          </Box>
        </Box>
      </Box>
      <Box
        ref={minimapRef}
        onDoubleClick={() => onViewChange({ s: 0, e: totalMs })}
        onMouseDown={handleTrackMouseDown}
        sx={{ position: 'relative', height: 38, cursor: 'crosshair' }}
      >
        {sortedSpans.map((s) => {
          const left = (offsetMsOf(s) / totalMs) * 100;
          const width = Math.max(0.6, (durationMsOf(s) / totalMs) * 100);
          const top = 2 + Math.min(4, depthBySpanId.get(s.spanId) ?? 0) * 8;
          const isError = s.statusCode === 'error';
          const color = isError ? theme.palette.error.main : spanColor(s.name);

          return (
            <Box
              key={s.spanId}
              sx={{
                position: 'absolute',
                height: 3,
                borderRadius: '2px',
                opacity: 0.85,
                left: `${left}%`,
                width: `${width}%`,
                top,
                bgcolor: color,
                ...(isError && {
                  boxShadow: `0 0 0 1.5px ${color}`,
                }),
              }}
            />
          );
        })}

        {/* Dim overlays for excluded regions */}
        <Box
          sx={{
            position: 'absolute',
            top: 0,
            bottom: 0,
            left: 0,
            width: `${(view.s / totalMs) * 100}%`,
            bgcolor: (t) => alpha(t.palette.action.disabledBackground, 0.1),
            zIndex: 1,
            pointerEvents: 'none',
          }}
        />
        <Box
          sx={{
            position: 'absolute',
            top: 0,
            bottom: 0,
            right: 0,
            width: `${((totalMs - view.e) / totalMs) * 100}%`,
            bgcolor: (t) => alpha(t.palette.action.disabledBackground, 0.1),
            zIndex: 1,
            pointerEvents: 'none',
          }}
        />

        {/* Brush border (no fill) */}
        <Box
          onMouseDown={startDrag('move')}
          sx={{
            position: 'absolute',
            top: -4,
            bottom: -4,
            left: `${(view.s / totalMs) * 100}%`,
            width: `${(visibleSpanMs / totalMs) * 100}%`,
            backgroundColor: 'transparent',
            border: 1,
            borderColor: (t) => alpha(t.palette.primary.main, 0.5),
            borderRadius: '5px',
            cursor: 'grab',
            zIndex: 2,
          }}
        >
          <Box
            onMouseDown={(e: ReactMouseEvent) => startDrag('l')(e)}
            sx={{
              position: 'absolute',
              top: 0,
              bottom: 0,
              left: -6,
              width: 11,
              cursor: 'ew-resize',
              display: 'grid',
              placeItems: 'center',
              '&::after': {
                content: '""',
                width: 2,
                height: '55%',
                borderRadius: radii.sm,
                bgcolor: 'primary.main',
                opacity: 0.75,
              },
            }}
          />
          <Box
            onMouseDown={(e: ReactMouseEvent) => startDrag('r')(e)}
            sx={{
              position: 'absolute',
              top: 0,
              bottom: 0,
              right: -6,
              width: 11,
              cursor: 'ew-resize',
              display: 'grid',
              placeItems: 'center',
              '&::after': {
                content: '""',
                width: 2,
                height: '55%',
                borderRadius: radii.sm,
                bgcolor: 'primary.main',
                opacity: 0.75,
              },
            }}
          />
        </Box>
      </Box>
    </Box>
  );
};

export default TraceMinimap;
