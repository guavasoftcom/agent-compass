import { useMemo, useState } from 'react';
import { Box, Typography, useTheme } from '@mui/material';
import StorageRoundedIcon from '@mui/icons-material/StorageRounded';
import ZoomInRoundedIcon from '@mui/icons-material/ZoomInRounded';
import type { Theme } from '@mui/material/styles';
import { SEVERITIES, type HistogramBucket, type LogHistogram, type Severity } from '../logsApi';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;

export const severityColor = (t: Theme, s: Severity): string => {
  switch (s) {
    case 'ERROR':
      return t.palette.error.main;
    case 'WARN':
      return t.palette.warning.main;
    case 'INFO':
      return t.palette.primary.main;
    default:
      return t.palette.text.disabled;
  }
};

const granLabel = (ms: number): string => {
  if (ms < HOUR) {
    return `${ms / 60_000} min`;
  }
  if (ms < DAY) {
    return `${ms / HOUR} hr`;
  }
  return `${ms / DAY} day`;
};

interface Props {
  data: LogHistogram | undefined;
  hidden: Set<Severity>;
  facetSeverity: { value: string; count: number }[];
  windowLabel: string;
  onToggleSeverity: (s: Severity) => void;
  /** Click a bar to drill the time window into that bucket's range. */
  onBarClick?: (bucket: HistogramBucket) => void;
}

interface Tip {
  x: number;
  y: number;
  bucketIdx: number;
}

const LogHistogram = ({ data, hidden, facetSeverity, windowLabel, onToggleSeverity, onBarClick }: Props) => {
  const theme = useTheme();
  const [tip, setTip] = useState<Tip | null>(null);

  const buckets = data?.buckets ?? [];
  const bucketMs = data?.bucketMs ?? HOUR;

  const max = useMemo(() => {
    let m = 1;
    buckets.forEach((b) => {
      const total = SEVERITIES.reduce((s, k) => s + (hidden.has(k) ? 0 : b[k]), 0);
      if (total > m) {
        m = total;
      }
    });
    return m;
  }, [buckets, hidden]);

  const useDate = bucketMs >= 6 * HOUR;
  const axisTicks = useMemo(() => {
    if (!buckets.length) {
      return [] as string[];
    }
    const step = Math.max(1, Math.round(buckets.length / 6));
    const out: string[] = [];
    for (let i = 0; i < buckets.length; i += step) {
      const t = new Date(buckets[i].t0);
      out.push(
        useDate
          ? t.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
          : t.toLocaleTimeString('en-US', { hour: 'numeric', hour12: true }).replace(' ', ''),
      );
    }
    return out;
  }, [buckets, useDate]);

  const sevCount = (s: Severity) => facetSeverity.find((f) => f.value === s)?.count ?? 0;

  const tipBucket = tip ? buckets[tip.bucketIdx] : null;

  return (
    <Box sx={{ position: 'relative' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 2, mb: 1.25, flexWrap: 'wrap' }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.1 }}>
          <Typography
            component="span"
            sx={{ fontFamily: "'Sora', sans-serif", fontSize: 11.5, fontWeight: 700, letterSpacing: '0.6px', textTransform: 'uppercase', color: 'text.secondary' }}
          >
            {`Log volume · ${windowLabel}`}
          </Typography>
          {data ? (
            <Typography component="span" sx={{ fontSize: 11, color: 'text.disabled' }}>
              {`· ${granLabel(bucketMs)} buckets`}
            </Typography>
          ) : null}
          <Box
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 0.6,
              height: 18,
              px: 0.9,
              borderRadius: 0.75,
              bgcolor: 'action.hover',
              color: 'text.disabled',
              fontSize: 10,
              fontWeight: 600,
            }}
            title="Bucketed server-side over the full window — independent of how many rows are loaded"
          >
            <StorageRoundedIcon sx={{ fontSize: 12 }} />
            server-aggregated
          </Box>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.75 }}>
          {SEVERITIES.map((s) => {
            const off = hidden.has(s);
            return (
              <Box
                key={s}
                onClick={() => onToggleSeverity(s)}
                sx={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 0.75,
                  fontSize: 12,
                  fontWeight: 500,
                  color: 'text.secondary',
                  cursor: 'pointer',
                  userSelect: 'none',
                  opacity: off ? 0.34 : 1,
                  textDecoration: off ? 'line-through' : 'none',
                  '&:hover': { color: 'text.primary' },
                }}
              >
                <Box sx={{ width: 10, height: 10, borderRadius: 0.75, bgcolor: severityColor(theme, s) }} />
                {`${s[0]}${s.slice(1).toLowerCase()}`}
                <Box component="span" sx={{ color: 'text.disabled', fontVariantNumeric: 'tabular-nums' }}>
                  {sevCount(s).toLocaleString()}
                </Box>
              </Box>
            );
          })}
        </Box>
      </Box>

      <Box
        sx={{ display: 'flex', alignItems: 'flex-end', gap: '2px', height: 84, opacity: data ? 1 : 0.4, transition: 'opacity .15s' }}
        onMouseLeave={() => setTip(null)}
      >
        {buckets.map((b, i) => {
          const total = SEVERITIES.reduce((s, k) => s + (hidden.has(k) ? 0 : b[k]), 0);
          const heightPct = Math.max(total ? 4 : 2, (total / max) * 100);
          return (
            <Box
              key={b.t0}
              onMouseEnter={(e) => {
                const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
                setTip({ x: r.left + r.width / 2, y: r.top, bucketIdx: i });
              }}
              onClick={() => onBarClick?.(b)}
              sx={{
                flex: 1,
                height: `${heightPct}%`,
                display: 'flex',
                flexDirection: 'column-reverse',
                borderRadius: '3px 3px 0 0',
                overflow: 'hidden',
                minHeight: 2,
                cursor: 'pointer',
                opacity: 0.92,
                '&:hover': { opacity: 1 },
              }}
            >
              {SEVERITIES.filter((k) => !hidden.has(k) && b[k] > 0).map((k) => (
                <Box key={k} sx={{ width: '100%', height: `${(b[k] / Math.max(1, total)) * 100}%`, bgcolor: severityColor(theme, k) }} />
              ))}
            </Box>
          );
        })}
      </Box>

      <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 0.9, fontSize: 11, color: 'text.disabled', fontVariantNumeric: 'tabular-nums' }}>
        {axisTicks.map((t, i) => (
          <Box component="span" key={`${t}-${i}`}>
            {t}
          </Box>
        ))}
      </Box>

      {tip && tipBucket ? (
        <Box
          sx={{
            position: 'fixed',
            zIndex: (t) => t.zIndex.tooltip,
            left: tip.x,
            top: tip.y - 12,
            transform: 'translate(-50%, -100%)',
            pointerEvents: 'none',
            bgcolor: 'background.paper',
            border: 1,
            borderColor: 'divider',
            borderRadius: 1.5,
            boxShadow: 6,
            px: 1.5,
            py: 1.25,
            minWidth: 150,
          }}
        >
          <Typography sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 700, fontSize: 12, mb: 0.75 }}>
            {bucketMs >= DAY
              ? new Date(tipBucket.t0).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
              : `${new Date(tipBucket.t0).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false })} – ${new Date(tipBucket.t1).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false })}`}
          </Typography>
          {SEVERITIES.filter((k) => tipBucket[k] > 0).map((k) => (
            <Box key={k} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 2.25, py: 0.25 }}>
              <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.9, color: 'text.secondary', fontSize: 12.5 }}>
                <Box sx={{ width: 9, height: 9, borderRadius: 0.5, bgcolor: severityColor(theme, k) }} />
                {k}
              </Box>
              <Box component="span" sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums', fontSize: 12.5 }}>
                {tipBucket[k]}
              </Box>
            </Box>
          ))}
          <Box
            sx={{
              display: 'flex',
              justifyContent: 'space-between',
              gap: 2.25,
              mt: 0.6,
              pt: 0.75,
              borderTop: 1,
              borderColor: 'divider',
              fontSize: 12.5,
            }}
          >
            <Box component="span" sx={{ color: 'text.secondary' }}>
              Total
            </Box>
            <Box component="span" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
              {SEVERITIES.reduce((s, k) => s + tipBucket[k], 0)}
            </Box>
          </Box>
          {onBarClick && SEVERITIES.reduce((s, k) => s + tipBucket[k], 0) > 0 ? (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.6, mt: 0.85, pt: 0.85, borderTop: 1, borderColor: 'divider', fontSize: 11, fontWeight: 600, color: 'primary.main' }}>
              <ZoomInRoundedIcon sx={{ fontSize: 13 }} />
              Click to zoom in
            </Box>
          ) : null}
        </Box>
      ) : null}
    </Box>
  );
};

export default LogHistogram;
