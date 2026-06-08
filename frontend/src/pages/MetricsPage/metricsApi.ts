// ---------------------------------------------------------------------------
// Metrics page API client
//
// The simplified Metrics page needs one call: the list of claude_code.* metrics,
// each with a headline value, a windowed trend, and any attribute splits.
// Defaults to LIVE; set VITE_METRICS_SAMPLE=1 to render the static fixtures
// instead (useful before the backend lands). See BACKEND.md for the contract.
// ---------------------------------------------------------------------------

import { METRICS, type MetricSeries } from './components/metricsSampleData';

const USE_SAMPLE_DATA =
  (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_METRICS_SAMPLE === '1';

/** Time window derived from the page's WindowSelection. */
export interface MetricsQueryParams {
  /** ISO-8601 start / end of the selected window. */
  from: string;
  to: string;
}

const toQuery = (params: MetricsQueryParams): string =>
  new URLSearchParams({ from: params.from, to: params.to }).toString();

async function getJSON<T>(url: string): Promise<T> {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText} — ${url}`);
  }
  return (await res.json()) as T;
}

/** GET /api/metrics/series — the claude_code.* metrics with trend + splits. */
export async function fetchMetrics(params: MetricsQueryParams): Promise<MetricSeries[]> {
  if (USE_SAMPLE_DATA) {
    return METRICS;
  }
  return getJSON<MetricSeries[]>(`/api/metrics/series?${toQuery(params)}`);
}
