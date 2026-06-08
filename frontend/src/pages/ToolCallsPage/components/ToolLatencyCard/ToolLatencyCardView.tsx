import { useEffect, useRef, useState } from 'react';
import { Box, Paper, Stack, Tooltip, Typography } from '@mui/material';

export interface LatencyBarSeries {
  label: string;
  data: number[];
  stack: string;
  color: string;
  valueFormatter: (value: number | null | undefined) => string;
}

export interface ToolLatencyCardViewProps {
  toolLabels: string[];
  series: LatencyBarSeries[];
  hasData: boolean;
  isLoading: boolean;
  height: number;
  yAxisWidth: number;
}

const formatSeconds = (value: number): string =>
  value < 1 ? `${(value * 1000).toFixed(0)} ms` : `${value.toFixed(2)} s`;

/**
 * Tool name that shows a tooltip with the full label only when the text is actually
 * truncated with an ellipsis (measured via scrollWidth > clientWidth).
 */
const EllipsisLabel = ({ text }: { text: string }) => {
  const ref = useRef<HTMLSpanElement | null>(null);
  const [truncated, setTruncated] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el) {
      return;
    }
    const check = () => setTruncated(el.scrollWidth > el.clientWidth);
    check();
    const observer = new ResizeObserver(check);
    observer.observe(el);
    return () => observer.disconnect();
  }, [text]);

  const label = (
    <Box
      component="span"
      ref={ref}
      sx={{
        display: 'block',
        flex: 1,
        minWidth: 0,
        fontSize: 14,
        fontWeight: 600,
        textAlign: 'left',
        color: 'text.primary',
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        whiteSpace: 'nowrap',
      }}
    >
      {text}
    </Box>
  );

  return truncated ? (
    <Tooltip title={text} placement="top" arrow disableInteractive>
      {label}
    </Tooltip>
  ) : (
    label
  );
};

/**
 * Aurora latency card — hand-built CSS bars (no @mui/x-charts) so it matches the
 * design preview exactly. Mirrors "Tools by failure rate": each row puts the tool
 * name and its p95 value on a line *above* a full-width thin bar. The bar is split
 * into a "Typical" (median) segment and a "Worst 5%" (p95 − p50) segment, scaled to
 * the largest total in the window.
 */
const ToolLatencyCardView = ({
  toolLabels,
  series,
  hasData,
  isLoading,
}: ToolLatencyCardViewProps) => {
  const typical = series[0];
  const worst = series[1];
  const typicalColor = typical?.color ?? '#1aa7dd';
  const worstColor = worst?.color ?? '#e84bc0';

  const totals = toolLabels.map(
    (_, index) => (typical?.data[index] ?? 0) + (worst?.data[index] ?? 0),
  );
  const max = Math.max(...totals, 0.0001);

  return (
    <Paper variant="outlined" sx={{ p: 2, height: '100%', display: 'flex', flexDirection: 'column' }}>
      <Stack
        direction="row"
        sx={{ alignItems: 'baseline', justifyContent: 'space-between', gap: 2, mb: 0.25 }}
      >
        <Typography variant="subtitle1">Latency per tool (s)</Typography>
        <Stack direction="row" spacing={1.5} sx={{ flexShrink: 0 }}>
          <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
            <Box sx={{ width: 9, height: 9, borderRadius: '3px', bgcolor: typicalColor }} />
            <Typography variant="caption" color="text.secondary">
              {typical?.label ?? 'Typical'}
            </Typography>
          </Stack>
          <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
            <Box sx={{ width: 9, height: 9, borderRadius: '3px', bgcolor: worstColor }} />
            <Typography variant="caption" color="text.secondary">
              {worst?.label ?? 'Worst 5%'}
            </Typography>
          </Stack>
        </Stack>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        Typical latency (median) plus the extra time the slowest 5% of calls take.
      </Typography>

      {!hasData && !isLoading ? (
        <Box sx={{ height: 200, display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
          <Typography color="text.secondary">No tool-scope spans in this window.</Typography>
        </Box>
      ) : (
        <Box sx={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'space-evenly' }}>
          {toolLabels.map((label, index) => {
            const typicalValue = typical?.data[index] ?? 0;
            const worstValue = worst?.data[index] ?? 0;
            const total = typicalValue + worstValue;
            return (
              <Box key={label}>
                <Stack
                  direction="row"
                  sx={{ alignItems: 'baseline', gap: 1, mb: 0.875 }}
                >
                  <EllipsisLabel text={label} />
                  <Typography
                    sx={{
                      fontSize: 13,
                      color: 'text.secondary',
                      fontVariantNumeric: 'tabular-nums',
                      whiteSpace: 'nowrap',
                      flexShrink: 0,
                    }}
                  >
                    <Box component="span" sx={{ color: 'text.primary', fontWeight: 700 }}>
                      {total > 0 ? formatSeconds(total) : '—'}
                    </Box>
                  </Typography>
                </Stack>
                <Box
                  sx={(theme) => ({
                    height: 10,
                    borderRadius: '6px',
                    bgcolor: theme.custom?.progressTrack ?? theme.palette.action.hover,
                    overflow: 'hidden',
                    display: 'flex',
                  })}
                >
                  <Box sx={{ width: `${(typicalValue / max) * 100}%`, bgcolor: typicalColor }} />
                  <Box sx={{ width: `${(worstValue / max) * 100}%`, bgcolor: worstColor }} />
                </Box>
              </Box>
            );
          })}
        </Box>
      )}
    </Paper>
  );
};

export default ToolLatencyCardView;
