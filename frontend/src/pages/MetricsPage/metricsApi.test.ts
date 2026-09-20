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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchMetricDistribution, fetchMetricFacets, fetchMetrics } from './metricsApi';

const FROM = '2026-09-18T12:00:00.000Z';
const TO = '2026-09-19T12:00:00.000Z';

const fetchMock = vi.fn();

const respondWith = (body: unknown): void => {
  fetchMock.mockResolvedValue({ ok: true, json: async () => body });
};

/** The query string of the single request the last call made, parsed. */
const lastRequest = (): { path: string; query: URLSearchParams } => {
  const url = fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0] as string;
  const [path, queryString] = url.split('?');
  return { path, query: new URLSearchParams(queryString) };
};

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('fetchMetrics (series query params)', () => {
  it('sends only the window when no filter or aggregation is active', async () => {
    respondWith([]);

    await fetchMetrics({ from: FROM, to: TO });

    const { path, query } = lastRequest();
    expect(path).toBe('/api/metrics/series');
    expect([...query.keys()].sort()).toEqual(['from', 'to']);
  });

  it('sends the repository only when one is selected', async () => {
    respondWith([]);

    await fetchMetrics({ from: FROM, to: TO, repositoryUrl: 'https://github.com/acme/widgets' });

    expect(lastRequest().query.get('repositoryUrl')).toBe('https://github.com/acme/widgets');
  });

  it('repeats `filter=key:value` once per chip and adds the filtered metric id', async () => {
    respondWith([]);

    await fetchMetrics({
      from: FROM,
      to: TO,
      filterMetricId: 'token',
      filters: [
        { key: 'model', value: 'claude-opus-4' },
        { key: 'terminal.type', value: 'vscode' },
      ],
    });

    const { query } = lastRequest();
    expect(query.getAll('filter')).toEqual(['model:claude-opus-4', 'terminal.type:vscode']);
    expect(query.get('filterMetricId')).toBe('token');
    // The old single-filter params are gone.
    expect(query.has('filterKey')).toBe(false);
    expect(query.has('filterValue')).toBe(false);
  });

  it('percent-encodes filter values, so a value containing & or : survives', async () => {
    respondWith([]);

    await fetchMetrics({
      from: FROM,
      to: TO,
      filterMetricId: 'token',
      filters: [{ key: 'terminal.type', value: 'a&b=c:d e' }],
    });

    const rawUrl = fetchMock.mock.calls[0][0] as string;
    expect(rawUrl).toContain('filter=terminal.type%3Aa%26b%3Dc%3Ad+e');
    expect(lastRequest().query.getAll('filter')).toEqual(['terminal.type:a&b=c:d e']);
  });

  it('sends none of filter / filterMetricId for an empty list, or for filters without a metric id', async () => {
    respondWith([]);

    await fetchMetrics({ from: FROM, to: TO, filterMetricId: 'token', filters: [] });
    expect([...lastRequest().query.keys()].sort()).toEqual(['from', 'to']);

    await fetchMetrics({ from: FROM, to: TO, filters: [{ key: 'model', value: 'x' }] });
    expect([...lastRequest().query.keys()].sort()).toEqual(['from', 'to']);
  });

  it('sends the aggregation group only for a non-sum aggregation, alongside the filters', async () => {
    respondWith([]);

    await fetchMetrics({
      from: FROM,
      to: TO,
      filterMetricId: 'token',
      filters: [{ key: 'model', value: 'claude-opus-4' }],
      aggMetricId: 'token',
      agg: 'p95',
    });

    const { query } = lastRequest();
    expect(query.get('aggMetricId')).toBe('token');
    expect(query.get('agg')).toBe('p95');
    expect(query.getAll('filter')).toEqual(['model:claude-opus-4']);

    await fetchMetrics({ from: FROM, to: TO, aggMetricId: 'token', agg: 'sum' });
    expect(lastRequest().query.has('agg')).toBe(false);
    expect(lastRequest().query.has('aggMetricId')).toBe(false);
  });

  it('throws with the status and url on a failed response', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 500, statusText: 'Server Error' });

    await expect(fetchMetrics({ from: FROM, to: TO })).rejects.toThrow(/500 Server Error/);
  });
});

describe('fetchMetricFacets', () => {
  it('asks GET /api/metrics/attributes by FULL metric name and unwraps `.attributes`', async () => {
    const attributes = [{ key: 'model', values: [{ value: 'claude-sonnet-4', count: 812 }] }];
    respondWith({ attributes });

    const facets = await fetchMetricFacets({ from: FROM, to: TO, metricName: 'claude_code.token.usage' });

    const { path, query } = lastRequest();
    expect(path).toBe('/api/metrics/attributes');
    expect(query.get('metric')).toBe('claude_code.token.usage');
    expect(query.get('from')).toBe(FROM);
    expect(query.get('to')).toBe(TO);
    expect(query.has('metricId')).toBe(false);
    expect(facets).toEqual(attributes);
  });

  it('treats a response with no attributes as nothing filterable', async () => {
    respondWith({});

    const facets = await fetchMetricFacets({ from: FROM, to: TO, metricName: 'claude_code.session.count' });

    expect(facets).toEqual([]);
  });

  it('adds the repository when selected', async () => {
    respondWith({ attributes: [] });

    await fetchMetricFacets({
      from: FROM,
      to: TO,
      repositoryUrl: 'https://github.com/acme/widgets',
      metricName: 'claude_code.token.usage',
    });

    expect(lastRequest().query.get('repositoryUrl')).toBe('https://github.com/acme/widgets');
  });
});

describe('fetchMetricDistribution', () => {
  it('sends `metric` (the full name), not metricId, and returns the points payload as-is', async () => {
    const body = { points: [{ ts: '2026-09-19T08:12:40Z', value: 13180, traceId: null }] };
    respondWith(body);

    const distribution = await fetchMetricDistribution({
      from: FROM,
      to: TO,
      metricName: 'claude_code.token.usage',
    });

    const { path, query } = lastRequest();
    expect(path).toBe('/api/metrics/distribution');
    expect(query.get('metric')).toBe('claude_code.token.usage');
    expect(query.has('metricId')).toBe(false);
    expect([...query.keys()].sort()).toEqual(['from', 'metric', 'to']);
    expect(distribution).toEqual(body);
  });

  it('adds the repository when selected', async () => {
    respondWith({ points: [] });

    await fetchMetricDistribution({
      from: FROM,
      to: TO,
      repositoryUrl: 'https://github.com/acme/widgets',
      metricName: 'claude_code.cost.usage',
    });

    expect(lastRequest().query.get('repositoryUrl')).toBe('https://github.com/acme/widgets');
  });
});
