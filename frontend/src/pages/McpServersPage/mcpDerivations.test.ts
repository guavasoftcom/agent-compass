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
import { colorForIndex } from '../../theme/theme';
import {
  buildServerColorIndexes,
  rollupByServer,
  UNKNOWN_SERVER,
  withShare,
} from './mcpDerivations';
import type { McpServerUsageRow } from '../../api';

const playwrightRows: McpServerUsageRow[] = [
  {
    server: 'playwright',
    tool: 'browser_evaluate',
    calls: 300,
    failures: 40,
    failureRate: 40 / 300,
    avgDurationMs: 900,
    p95DurationMs: 13100,
    totalBytes: 4_500_000,
    estimatedTokens: 1_125_000,
  },
  {
    server: 'playwright',
    tool: 'browser_click',
    calls: 160,
    failures: 14,
    failureRate: 14 / 160,
    avgDurationMs: 250,
    p95DurationMs: 900,
    totalBytes: 2_000_000,
    estimatedTokens: 500_000,
  },
];

const codeGraphContextRows: McpServerUsageRow[] = [
  {
    server: 'CodeGraphContext',
    tool: 'find_dead_code',
    calls: 8,
    failures: 2,
    failureRate: 0.25,
    avgDurationMs: 400,
    p95DurationMs: 1200,
    totalBytes: 20_000,
    estimatedTokens: 5_000,
  },
];

describe('rollupByServer', () => {
  it('sums calls, failures, and bytes across a server\'s tools', () => {
    const rollups = rollupByServer(playwrightRows);

    expect(rollups).toHaveLength(1);
    expect(rollups[0]).toMatchObject({
      server: 'playwright',
      calls: 460,
      failures: 54,
      totalBytes: 6_500_000,
      estimatedTokens: 1_625_000,
      toolCount: 2,
    });
  });

  it('recomputes failureRate from the rolled-up totals rather than averaging per-tool rates', () => {
    const rollups = rollupByServer(playwrightRows);

    // 54 failures / 460 calls, not the average of 40/300 and 14/160.
    expect(rollups[0].failureRate).toBeCloseTo(54 / 460);
  });

  it('weights avgDurationMs by each tool\'s own call count', () => {
    const rollups = rollupByServer(playwrightRows);

    const expected = (900 * 300 + 250 * 160) / 460;
    expect(rollups[0].avgDurationMs).toBeCloseTo(expected);
  });

  it('takes the max p95DurationMs across a server\'s tools, not an average', () => {
    const rollups = rollupByServer(playwrightRows);

    expect(rollups[0].p95DurationMs).toBe(13100);
  });

  it('keeps servers separate and orders by total calls descending', () => {
    const rollups = rollupByServer([...playwrightRows, ...codeGraphContextRows]);

    expect(rollups.map((rollup) => rollup.server)).toEqual(['playwright', 'CodeGraphContext']);
  });

  it('returns an empty list for no rows', () => {
    expect(rollupByServer([])).toEqual([]);
  });

  it('buckets a row with no server identity under the unknown server', () => {
    const rollups = rollupByServer([
      {
        server: UNKNOWN_SERVER,
        tool: 'mcp_tool',
        calls: 3,
        failures: 0,
        failureRate: 0,
        avgDurationMs: 100,
        p95DurationMs: 100,
        totalBytes: 0,
        estimatedTokens: 0,
      },
    ]);

    expect(rollups).toEqual([
      {
        server: UNKNOWN_SERVER,
        calls: 3,
        failures: 0,
        failureRate: 0,
        avgDurationMs: 100,
        p95DurationMs: 100,
        totalBytes: 0,
        estimatedTokens: 0,
        toolCount: 1,
      },
    ]);
  });
});

describe('withShare', () => {
  it('computes each server\'s share of total calls', () => {
    const rollups = rollupByServer([...playwrightRows, ...codeGraphContextRows]);
    const { rows, total } = withShare(rollups);

    expect(total).toBe(468);
    expect(rows[0].share).toBeCloseTo((460 / 468) * 100);
    expect(rows[1].share).toBeCloseTo((8 / 468) * 100);
  });

  it('reports zero share instead of dividing by zero when there are no calls', () => {
    const { rows, total } = withShare([
      {
        server: 'playwright',
        calls: 0,
        failures: 0,
        failureRate: 0,
        avgDurationMs: 0,
        p95DurationMs: 0,
        totalBytes: 0,
        estimatedTokens: 0,
        toolCount: 0,
      },
    ]);

    expect(total).toBe(0);
    expect(rows[0].share).toBe(0);
  });
});

describe('buildServerColorIndexes', () => {
  it('orders servers by total calls descending', () => {
    const rollups = rollupByServer([...playwrightRows, ...codeGraphContextRows]);
    const indexes = buildServerColorIndexes(rollups);

    expect([...indexes.entries()]).toEqual([['playwright', 0], ['CodeGraphContext', 1]]);
  });

  it('breaks ties on call count by server name', () => {
    const tied = rollupByServer([
      { ...playwrightRows[0], server: 'zeta', tool: 'a', calls: 10, failures: 0, failureRate: 0 },
      { ...playwrightRows[0], server: 'alpha', tool: 'b', calls: 10, failures: 0, failureRate: 0 },
    ]);
    const indexes = buildServerColorIndexes(tied);

    expect([...indexes.entries()]).toEqual([['alpha', 0], ['zeta', 1]]);
  });

  it('returns an empty map for no rows', () => {
    expect(buildServerColorIndexes([]).size).toBe(0);
  });

  it('is usable to resolve the same color a caller would pass to colorForIndex', () => {
    const rollups = rollupByServer(playwrightRows);
    const indexes = buildServerColorIndexes(rollups);

    expect(colorForIndex(indexes.get('playwright') ?? -1)).toBe(colorForIndex(0));
  });
});
