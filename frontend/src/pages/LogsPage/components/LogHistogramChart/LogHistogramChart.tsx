import { useMemo } from 'react';
import { SEVERITIES, type HistogramBucket, type LogHistogram, type Severity } from '../../logsApi';
import LogHistogramChartView from './LogHistogramChartView';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;

const formatGranularity = (ms: number): string => {
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

/**
 * Log-volume histogram container — turns the raw LogHistogram payload into the view model
 * (bucket list, bar max, axis ticks, per-severity totals, granularity) that
 * LogHistogramChartView renders. No data fetching; pure derivation.
 *
 * `hidden` is derived in LogsPageView as the complement of the active severity selection
 * and controls which severity segments are dimmed in the chart. Clicking a legend chip
 * calls onToggleSeverity, which toggles that severity in the shared page filter.
 */
const LogHistogramChart = ({ data, hidden, facetSeverity, windowLabel, onToggleSeverity, onBarClick }: Props) => {
  const buckets = useMemo<HistogramBucket[]>(() => data?.buckets ?? [], [data]);
  const bucketMs = data?.bucketMs ?? HOUR;
  const useDate = bucketMs >= 6 * HOUR;

  const max = useMemo(() => {
    let highest = 1;
    buckets.forEach((b) => {
      const total = SEVERITIES.reduce((sum, k) => sum + (hidden.has(k) ? 0 : b[k]), 0);
      if (total > highest) {
        highest = total;
      }
    });
    return highest;
  }, [buckets, hidden]);

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

  const severityCounts = useMemo(
    () =>
      SEVERITIES.reduce((acc, s) => {
        acc[s] = facetSeverity.find((f) => f.value === s)?.count ?? 0;
        return acc;
      }, {} as Record<Severity, number>),
    [facetSeverity],
  );

  return (
    <LogHistogramChartView
      buckets={buckets}
      max={max}
      axisTicks={axisTicks}
      hidden={hidden}
      windowLabel={windowLabel}
      hasData={data !== undefined}
      granularityLabel={formatGranularity(bucketMs)}
      severityCounts={severityCounts}
      tooltipDateOnly={bucketMs >= DAY}
      onToggleSeverity={onToggleSeverity}
      onBarClick={onBarClick}
    />
  );
};

export default LogHistogramChart;
