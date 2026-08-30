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
import { useRef, useState } from 'react';
import { Box, Typography, useTheme } from '@mui/material';
import BarChartIcon from '@mui/icons-material/BarChart';
import {
  formatDuration,
  type TraceHistogram,
  type TraceHistogramBucket,
} from '../../tracesApi';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';

export type HistogramSeries = 'ok' | 'error' | 'p95';

export interface TraceHistogramViewProps {
  data: TraceHistogram | undefined;
  hiddenSeries: Set<HistogramSeries>;
  windowLabel: string;
  onToggleSeries: (series: HistogramSeries) => void;
  onBarClick: (bucket: TraceHistogramBucket) => void;
}

const BAR_HEIGHT = 84;

const TraceHistogramView = ({
  data,
  hiddenSeries: hidden,
  windowLabel,
  onToggleSeries: onToggle,
  onBarClick,
}: TraceHistogramViewProps) => {
  const theme = useTheme();
  const [tip, setTip] = useState<{
    x: number;
    y: number;
    b: TraceHistogramBucket;
  } | null>(null);
  const barsRef = useRef<HTMLDivElement>(null);

  const buckets = data?.buckets ?? [];
  const maxTotal = Math.max(1, ...buckets.map((b) => b.ok + b.error));
  const p95Line = Math.max(1, data?.p95Ms ?? 1) * 1.25;
  const okColor = theme.palette.primary.main;
  const errorColor = theme.palette.error.main;
  const warnColor = theme.palette.warning.main;

  const total = data?.total ?? 0;
  const errors = data?.errorCount ?? 0;
  const errorRate = total ? (errors / total) * 100 : 0;

  // smoothed p95 polyline (3-wide moving average; interpolate gaps)
  const rawP95Values = buckets.map((b) => (b.p95Ms > 0 ? b.p95Ms : null));
  const smoothedP95Values = rawP95Values.map((_, i) => {
    let sum = 0;
    let count = 0;
    for (let k = i - 1; k <= i + 1; k += 1) {
      const value = rawP95Values[k];
      if (value != null) {
        sum += value;
        count += 1;
      }
    }
    return count ? sum / count : null;
  });
  const bucketCount = buckets.length || 1;
  let path = '';
  let started = false;
  smoothedP95Values.forEach((smoothedValue, i) => {
    if (smoothedValue == null) {
      return;
    }
    const x = ((i + 0.5) / bucketCount) * 100;
    const y = 100 - Math.min(96, (smoothedValue / p95Line) * 90);
    path += `${started ? ' L' : 'M'}${x.toFixed(2)} ${y.toFixed(2)}`;
    started = true;
  });

  const legend: Array<{
    series: HistogramSeries;
    label: string;
    color: string;
    count: number | null;
    line?: boolean;
  }> = [
    { series: 'ok', label: 'OK', color: okColor, count: total - errors },
    { series: 'error', label: 'Error', color: errorColor, count: errors },
    { series: 'p95', label: 'p95 latency', color: warnColor, count: null, line: true },
  ];

  const formatAxisLabel = (isoTimestamp: string) => {
    const date = new Date(isoTimestamp);
    const hours = date.getHours();
    return `${hours % 12 === 0 ? 12 : hours % 12} ${hours < 12 ? 'AM' : 'PM'}`;
  };
  const axisFractions = [0, 0.25, 0.5, 0.75, 1];

  return (
    <Box>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 2,
          mb: 1.1,
          flexWrap: 'wrap',
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.1 }}>
          <BarChartIcon sx={{ fontSize: 16, color: 'text.secondary' }} />
          <Typography
            component="span"
            sx={{
              typography: 'eyebrow',
              color: 'text.secondary',
            }}
          >
            Trace throughput · {windowLabel}
          </Typography>
          <Box
            component="span"
            sx={{
              typography: 'mono',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 1.25,
              fontSize: 11,
              color: 'text.disabled',
            }}
          >
            p50{' '}
            <b style={{ color: theme.palette.text.primary }}>
              {formatDuration((data?.p50Ms ?? 0) * 1e6)}
            </b>
            · p95{' '}
            <b style={{ color: theme.palette.text.primary }}>
              {formatDuration((data?.p95Ms ?? 0) * 1e6)}
            </b>
            ·{' '}
            <span style={{ color: errorColor }}>{errorRate.toFixed(1)}% errors</span>
          </Box>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.75 }}>
          {legend.map((item) => {
            const off = hidden.has(item.series);
            return (
              <Box
                key={item.series}
                onClick={() => onToggle(item.series)}
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
                <Box
                  sx={{
                    width: 10,
                    height: item.line ? 3 : 10,
                    borderRadius: item.line ? radii.sm : radii.xs,
                    bgcolor: item.color,
                  }}
                />
                {item.label}
                {item.count != null ? (
                  <Box
                    component="span"
                    sx={{
                      color: 'text.disabled',
                      fontVariantNumeric: 'tabular-nums',
                    }}
                  >
                    {item.count.toLocaleString()}
                  </Box>
                ) : null}
              </Box>
            );
          })}
        </Box>
      </Box>

      <Box
        ref={barsRef}
        sx={{
          position: 'relative',
          display: 'flex',
          alignItems: 'flex-end',
          gap: '2px',
          height: BAR_HEIGHT,
        }}
      >
        {buckets.map((b) => {
          const okH = hidden.has('ok') ? 0 : (b.ok / maxTotal) * BAR_HEIGHT;
          const erH = hidden.has('error')
            ? 0
            : (b.error / maxTotal) * BAR_HEIGHT;
          return (
            <Box
              key={b.t0}
              onClick={() => onBarClick(b)}
              onMouseMove={(e) => {
                const host = barsRef.current?.getBoundingClientRect();
                setTip({ x: e.clientX, y: host ? host.top : e.clientY, b });
              }}
              onMouseLeave={() => setTip(null)}
              sx={{
                flex: 1,
                display: 'flex',
                flexDirection: 'column-reverse',
                alignItems: 'stretch',
                borderRadius: '3px 3px 0 0',
                overflow: 'hidden',
                minHeight: 2,
                cursor: 'pointer',
                opacity: 0.92,
                '&:hover': { opacity: 1 },
              }}
            >
              <Box sx={{ height: okH, bgcolor: okColor }} />
              <Box sx={{ height: erH, bgcolor: errorColor }} />
            </Box>
          );
        })}
        {!hidden.has('p95') && path ? (
          <Box
            component="svg"
            viewBox="0 0 100 100"
            preserveAspectRatio="none"
            sx={{
              position: 'absolute',
              inset: 0,
              width: '100%',
              height: '100%',
              pointerEvents: 'none',
              overflow: 'visible',
            }}
          >
            <path
              d={path}
              fill="none"
              stroke={warnColor}
              strokeWidth={1.4}
              vectorEffect="non-scaling-stroke"
              opacity={0.7}
            />
          </Box>
        ) : null}
      </Box>

      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          mt: 0.9,
          fontSize: 11,
          color: 'text.disabled',
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        {axisFractions.map((fraction) => {
          const bucketIndex = Math.min(
            buckets.length - 1,
            Math.floor(fraction * (buckets.length - 1)),
          );
          const isoTimestamp = buckets[bucketIndex]?.t0;
          return <span key={fraction}>{isoTimestamp ? formatAxisLabel(isoTimestamp) : ''}</span>;
        })}
      </Box>

      {tip ? (
        <Box
          sx={{
            position: 'fixed',
            zIndex: 60,
            left: Math.min(tip.x + 14, window.innerWidth - 190),
            top: tip.y - 8,
            transform: 'translateY(-100%)',
            bgcolor: 'background.paper',
            border: 1,
            borderColor: 'divider',
            borderRadius: radii.lg,
            px: 1.5,
            py: 1.25,
            boxShadow: 6,
            pointerEvents: 'none',
            minWidth: 150,
          }}
        >
          <Typography
            sx={{
              fontFamily: fontFamilies.display,
              fontWeight: 700,
              fontSize: 12,
              mb: 0.75,
            }}
          >
            {new Date(tip.b.t0).toLocaleTimeString('en-US', {
              hour: '2-digit',
              minute: '2-digit',
              hour12: false,
            })}
            {' – '}
            {new Date(tip.b.t1).toLocaleTimeString('en-US', {
              hour: '2-digit',
              minute: '2-digit',
              hour12: false,
            })}
          </Typography>
          {[
            { l: 'OK', v: tip.b.ok, c: okColor },
            { l: 'Error', v: tip.b.error, c: errorColor },
            { l: 'p95', v: formatDuration(tip.b.p95Ms * 1e6), c: warnColor },
          ].map((r) => (
            <Box
              key={r.l}
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 2.25,
                py: 0.25,
              }}
            >
              <Box
                component="span"
                sx={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 0.9,
                  color: 'text.secondary',
                  fontSize: 12.5,
                }}
              >
                <Box
                  sx={{ width: 9, height: 9, borderRadius: radii.xs, bgcolor: r.c }}
                />
                {r.l}
              </Box>
              <Box
                component="span"
                sx={{
                  fontVariantNumeric: 'tabular-nums',
                  fontWeight: 600,
                  fontSize: 12.5,
                }}
              >
                {r.v}
              </Box>
            </Box>
          ))}
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 0.5,
              mt: 0.75,
              pt: 0.75,
              borderTop: 1,
              borderColor: 'divider',
              fontSize: 11,
              fontWeight: 600,
              color: 'primary.main',
            }}
          >
            Click to zoom in
          </Box>
        </Box>
      ) : null}
    </Box>
  );
};

export default TraceHistogramView;
