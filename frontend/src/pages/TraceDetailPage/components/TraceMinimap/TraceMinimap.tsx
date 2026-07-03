import { useRef } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { Box, alpha, useTheme } from '@mui/material';
import type { SpanRow } from '../../../../api';
import { spanColor, SERVICE_LEGEND } from '../../../TracesPage/components/traceColors';
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
  const offsetMsOf = (s: SpanRow) =>
    Date.parse(s.startTimestamp) - earliestStartMs;
  const durationMsOf = (s: SpanRow) => s.durationNanos / 1e6;

  // Drag the brush body to pan or either edge to resize the zoom window.
  const startBrushDrag = (mode: 'move' | 'l' | 'r') => (e: ReactMouseEvent) => {
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
      } else {
        end = Math.min(
          totalMs,
          Math.max(endView + deltaMs, startView + minimumZoomMs),
        );
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
        <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1.2 }}>
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
              <Box sx={{ width: 8, height: 8, borderRadius: '2px', bgcolor: color }} />
              {label}
            </Box>
          ))}
        </Box>
        <Box
          component="span"
          sx={{
            typography: 'eyebrowSm',
            fontWeight: 600,
            color: 'text.disabled',
            pointerEvents: 'none',
          }}
        >
          drag to zoom · dbl-click resets
        </Box>
      </Box>
      <Box
        ref={minimapRef}
        onDoubleClick={() => onViewChange({ s: 0, e: totalMs })}
        sx={{ position: 'relative', height: 38 }}
      >
        {spans.map((s) => {
          const left = (offsetMsOf(s) / totalMs) * 100;
          const width = Math.max(0.6, (durationMsOf(s) / totalMs) * 100);
          const top = 2 + Math.min(4, depthBySpanId.get(s.spanId) ?? 0) * 8;
          const color =
            s.statusCode === 'error'
              ? theme.palette.error.main
              : spanColor(s.name);
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
              }}
            />
          );
        })}
        <Box
          onMouseDown={startBrushDrag('move')}
          sx={{
            position: 'absolute',
            top: -4,
            bottom: -4,
            left: `${(view.s / totalMs) * 100}%`,
            width: `${(visibleSpanMs / totalMs) * 100}%`,
            bgcolor: (t) => alpha(t.palette.primary.main, 0.13),
            border: 1,
            borderColor: (t) => alpha(t.palette.primary.main, 0.32),
            borderRadius: '5px',
            cursor: 'grab',
          }}
        >
          <Box
            onMouseDown={startBrushDrag('l')}
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
            onMouseDown={startBrushDrag('r')}
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
