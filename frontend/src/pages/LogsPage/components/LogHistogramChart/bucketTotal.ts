import { SEVERITIES, type HistogramBucket, type Severity } from '../../logsApi';

/**
 * Sum a histogram bucket's severity counts. Pass `hiddenSeverities` to exclude
 * legend-muted severities (the visible total); omit it for the full total.
 */
export const bucketTotal = (
  bucket: HistogramBucket,
  hiddenSeverities?: Set<Severity>,
): number =>
  SEVERITIES.reduce(
    (sum, severity) =>
      sum + (hiddenSeverities?.has(severity) ? 0 : bucket[severity]),
    0,
  );
