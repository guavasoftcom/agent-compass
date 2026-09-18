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
import { nestDispatchedRows } from './nestDispatchedRows';

// A minimal row shape exercising the core algorithm directly, independent of
// either page's own DTO. `traceId` is nullable the way SessionsPage's rows
// are; TraceDetailPage's own pre-filtered rows are covered by
// switchTraceRows.test.ts instead of duplicated here.
interface TestRow {
  timestamp: string;
  traceId: string | null;
  dispatchingTraceId: string | null;
}

const rowAt = (
  timestamp: string,
  traceId: string | null,
  dispatchingTraceId: string | null = null,
): TestRow => ({ timestamp, traceId, dispatchingTraceId });

const accessors = {
  traceIdOf: (row: TestRow) => row.traceId,
  dispatchingTraceIdOf: (row: TestRow) => row.dispatchingTraceId,
};

describe('nestDispatchedRows', () => {
  it('takes the fast path and leaves every row at depth 0 when nothing dispatches', () => {
    const rows = [rowAt('2026-09-01T00:00:00.000Z', 'trace-a'), rowAt('2026-09-01T00:01:00.000Z', 'trace-b')];

    expect(nestDispatchedRows(rows, accessors)).toEqual([
      { row: rows[0], depth: 0, railBelow: [], originalIndex: 0 },
      { row: rows[1], depth: 0, railBelow: [], originalIndex: 1 },
    ]);
  });

  it('reorders one child directly beneath its earlier dispatcher', () => {
    const dispatcher = rowAt('2026-09-01T00:00:00.000Z', 'trace-dispatcher');
    const unrelated = rowAt('2026-09-01T00:01:00.000Z', 'trace-unrelated');
    const child = rowAt('2026-09-01T00:02:00.000Z', 'trace-child', 'trace-dispatcher');

    const nested = nestDispatchedRows([dispatcher, unrelated, child], accessors);

    expect(nested.map((entry) => entry.row.traceId)).toEqual([
      'trace-dispatcher',
      'trace-child',
      'trace-unrelated',
    ]);
    expect(nested[1].depth).toBe(1);
    expect(nested[1].railBelow).toEqual([false]);
    expect(nested[1].originalIndex).toBe(2);
  });

  it('keeps two children chronological under one dispatcher, clearing railBelow only on the last', () => {
    const dispatcher = rowAt('2026-09-01T00:00:00.000Z', 'trace-dispatcher');
    const firstChild = rowAt('2026-09-01T00:01:00.000Z', 'trace-child-1', 'trace-dispatcher');
    const secondChild = rowAt('2026-09-01T00:02:00.000Z', 'trace-child-2', 'trace-dispatcher');

    const nested = nestDispatchedRows([dispatcher, firstChild, secondChild], accessors);

    expect(nested[1].railBelow).toEqual([true]);
    expect(nested[2].railBelow).toEqual([false]);
  });

  it('nests a chained dispatch to depth 2', () => {
    const dispatcher = rowAt('2026-09-01T00:00:00.000Z', 'trace-dispatcher');
    const child = rowAt('2026-09-01T00:01:00.000Z', 'trace-child', 'trace-dispatcher');
    const grandchild = rowAt('2026-09-01T00:02:00.000Z', 'trace-grandchild', 'trace-child');

    const nested = nestDispatchedRows([dispatcher, child, grandchild], accessors);

    expect(nested[2].depth).toBe(2);
    expect(nested[2].railBelow).toEqual([false, false]);
  });

  it('leaves a row top-level when its claimed dispatcher trace is absent from the row list', () => {
    const orphan = rowAt('2026-09-01T00:00:00.000Z', 'trace-orphan', 'trace-missing');

    expect(nestDispatchedRows([orphan], accessors)).toEqual([
      { row: orphan, depth: 0, railBelow: [], originalIndex: 0 },
    ]);
  });

  it('leaves a self-referencing row top-level', () => {
    const selfReferencing = rowAt('2026-09-01T00:00:00.000Z', 'trace-self', 'trace-self');

    expect(nestDispatchedRows([selfReferencing], accessors)).toEqual([
      { row: selfReferencing, depth: 0, railBelow: [], originalIndex: 0 },
    ]);
  });

  it('ignores a dispatcher that appears after its claimed child in array order', () => {
    const child = rowAt('2026-09-01T00:00:00.000Z', 'trace-child', 'trace-later-dispatcher');
    const laterDispatcher = rowAt('2026-09-01T00:01:00.000Z', 'trace-later-dispatcher');

    expect(nestDispatchedRows([child, laterDispatcher], accessors)).toEqual([
      { row: child, depth: 0, railBelow: [], originalIndex: 0 },
      { row: laterDispatcher, depth: 0, railBelow: [], originalIndex: 1 },
    ]);
  });

  it('attaches a dispatched child to the earliest of two rows sharing one trace id', () => {
    const earlierShared = rowAt('2026-09-01T00:00:00.000Z', 'trace-shared');
    const laterShared = rowAt('2026-09-01T00:01:00.000Z', 'trace-shared');
    const child = rowAt('2026-09-01T00:02:00.000Z', 'trace-child', 'trace-shared');

    const nested = nestDispatchedRows([earlierShared, laterShared, child], accessors);

    expect(nested.map((entry) => entry.row.traceId)).toEqual([
      'trace-shared',
      'trace-child',
      'trace-shared',
    ]);
    expect(nested[1].depth).toBe(1);
  });

  it('never treats a row with no traceId as a nesting parent', () => {
    const untracedDispatcher = rowAt('2026-09-01T00:00:00.000Z', null);
    const claimedChild = rowAt('2026-09-01T00:01:00.000Z', 'trace-child', null);

    expect(nestDispatchedRows([untracedDispatcher, claimedChild], accessors)).toEqual([
      { row: untracedDispatcher, depth: 0, railBelow: [], originalIndex: 0 },
      { row: claimedChild, depth: 0, railBelow: [], originalIndex: 1 },
    ]);
  });
});
