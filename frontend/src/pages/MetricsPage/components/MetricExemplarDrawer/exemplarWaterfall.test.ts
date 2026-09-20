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
import { describe, expect, it } from 'vitest';
import type { SpanRow, TraceRow } from '../../../../api';
import {
  EXEMPLAR_WATERFALL_MAX_ROWS,
  buildExemplarWaterfall,
  exemplarStatusOf,
  summarizeExemplarModels,
} from './exemplarWaterfall';

const TRACE_START_MS = Date.parse('2026-09-19T08:00:00.000Z');

const makeSpan = (
  spanId: string,
  parentSpanId: string | null,
  offsetMs: number,
  durationMs: number,
  overrides: Partial<SpanRow> = {},
): SpanRow => ({
  id: 0,
  spanId,
  parentSpanId,
  traceId: 'trace-1',
  name: spanId,
  kind: 'internal',
  startTimestamp: new Date(TRACE_START_MS + offsetMs).toISOString(),
  endTimestamp: new Date(TRACE_START_MS + offsetMs + durationMs).toISOString(),
  durationNanos: durationMs * 1e6,
  statusCode: 'ok',
  statusMessage: null,
  scopeName: null,
  attributes: null,
  events: null,
  resourceAttributes: null,
  ...overrides,
});

describe('buildExemplarWaterfall', () => {
  it('returns no rows for an empty trace', () => {
    expect(buildExemplarWaterfall([])).toEqual({ rows: [], hiddenSpanCount: 0 });
  });

  it('scales bars to the drawn rows, not to background work that runs long after them', () => {
    const { rows, hiddenSpanCount } = buildExemplarWaterfall([
      makeSpan('root', null, 0, 1000),
      makeSpan('tool', 'root', 100, 400),
      // Trimmed (depth 2) and 24 minutes later: must not stretch the axis.
      makeSpan('background', 'tool', 1_400_000, 5000),
    ]);

    expect(hiddenSpanCount).toBe(1);
    expect(rows[0]).toMatchObject({ left: 0, right: 100, width: 100 });
    expect(rows[1]).toMatchObject({ left: 10, right: 50, width: 40 });
  });

  it('places bars against the drawn rows\' duration', () => {
    const { rows } = buildExemplarWaterfall([
      makeSpan('root', null, 0, 1000),
      makeSpan('child', 'root', 250, 500),
    ]);

    expect(rows.map((row) => row.span.spanId)).toEqual(['root', 'child']);
    expect(rows[0]).toMatchObject({ left: 0, right: 100, width: 100 });
    expect(rows[1]).toMatchObject({ left: 25, right: 75, width: 50 });
  });

  it('walks depth-first in start order and reports depth and the full-trace index', () => {
    const { rows } = buildExemplarWaterfall([
      makeSpan('late', 'root', 600, 100),
      makeSpan('root', null, 0, 1000),
      makeSpan('early', 'root', 100, 100),
    ]);

    expect(rows.map((row) => [row.span.spanId, row.depth, row.indexLabel])).toEqual([
      ['root', 0, 1],
      ['early', 1, 2],
      ['late', 1, 3],
    ]);
  });

  it('stops below the direct children and counts the spans it left out', () => {
    const { rows, hiddenSpanCount } = buildExemplarWaterfall([
      makeSpan('root', null, 0, 1000),
      makeSpan('tool', 'root', 100, 400),
      makeSpan('tool-execution', 'tool', 120, 300),
    ]);

    expect(rows.map((row) => row.span.spanId)).toEqual(['root', 'tool']);
    expect(hiddenSpanCount).toBe(1);
  });

  it('counts errored spans below a row even when they were trimmed', () => {
    const { rows } = buildExemplarWaterfall([
      makeSpan('root', null, 0, 1000),
      makeSpan('tool', 'root', 100, 400),
      makeSpan('tool-execution', 'tool', 120, 300, { statusCode: 'error' }),
    ]);

    expect(rows.find((row) => row.span.spanId === 'tool')?.descendantErrorCount).toBe(1);
    expect(rows.find((row) => row.span.spanId === 'root')?.descendantErrorCount).toBe(1);
  });

  it('caps the row count', () => {
    const manyChildren = Array.from({ length: EXEMPLAR_WATERFALL_MAX_ROWS + 10 }, (_, index) =>
      makeSpan(`child-${index}`, 'root', index, 1),
    );
    const { rows, hiddenSpanCount } = buildExemplarWaterfall([makeSpan('root', null, 0, 1000), ...manyChildren]);

    expect(rows).toHaveLength(EXEMPLAR_WATERFALL_MAX_ROWS);
    expect(hiddenSpanCount).toBe(11);
  });
});

describe('summarizeExemplarModels', () => {
  it('is null when no span names a model', () => {
    expect(summarizeExemplarModels([makeSpan('root', null, 0, 10)])).toBeNull();
  });

  it('names a single model in short form', () => {
    const spans = [makeSpan('call', null, 0, 10, { attributes: { model: 'claude-sonnet-4' } })];

    expect(summarizeExemplarModels(spans)).toBe('Sonnet 4');
  });

  it('adds a suffix when the trace switched models', () => {
    const spans = [
      makeSpan('first', null, 0, 10, { attributes: { model: 'claude-sonnet-4' } }),
      makeSpan('second', null, 20, 10, { attributes: { 'gen_ai.request.model': 'claude-opus-4' } }),
      makeSpan('third', null, 40, 10, { attributes: { model: 'claude-sonnet-4' } }),
    ];

    expect(summarizeExemplarModels(spans)).toBe('Sonnet 4 +1');
  });
});

describe('exemplarStatusOf', () => {
  const summary = (overrides: Partial<TraceRow>): TraceRow => ({
    traceId: 'trace-1',
    startTimestamp: '2026-09-19T08:00:00Z',
    rootSpanName: 'claude_code.interaction',
    rootSpanId: 'root',
    sessionId: null,
    spanCount: 3,
    durationNanos: 1e9,
    errorCount: 0,
    totalTokens: 0,
    totalCostUsd: 0,
    firstUserPrompt: null,
    inProgress: false,
    ...overrides,
  });

  it('prefers the summary, with running above error above ok', () => {
    expect(exemplarStatusOf(summary({ inProgress: true, errorCount: 2 }), [])).toBe('running');
    expect(exemplarStatusOf(summary({ errorCount: 2 }), [])).toBe('error');
    expect(exemplarStatusOf(summary({}), [makeSpan('root', null, 0, 10, { statusCode: 'error' })])).toBe('ok');
  });

  it('falls back to the spans when there is no summary', () => {
    expect(exemplarStatusOf(null, [makeSpan('root', null, 0, 10, { statusCode: 'error' })])).toBe('error');
    expect(exemplarStatusOf(undefined, [makeSpan('root', null, 0, 10)])).toBe('ok');
  });

  it('is unknown with neither', () => {
    expect(exemplarStatusOf(null, undefined)).toBeNull();
    expect(exemplarStatusOf(undefined, [])).toBeNull();
  });
});
