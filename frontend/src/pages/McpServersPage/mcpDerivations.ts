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
// Pure derivations for the MCP servers page. Kept out of the container so the
// per-server rollup, share maths, and color assignment can be unit-tested
// without mounting the page.

import type { McpServerUsageRow } from '../../api';

/** Server bucket for rows whose identity couldn't be resolved from `tool_parameters`. */
export const UNKNOWN_SERVER = 'unknown';

/** P95 duration at or above which `McpToolDetailTable` flags a row's P95 cell as slow. */
export const SLOW_P95_MS = 5000;

/**
 * One server's rollup across all of its (server, tool) rows — the shape the report's own
 * "MCP servers" section rolls up to, mirrored here so the dashboard answers the same
 * "is this server worth its cost" question at a glance. `avgDurationMs` is a calls-weighted
 * average across the server's tools; `p95DurationMs` is the max of each tool's own p95
 * (a conservative "worst tool" figure — averaging p95s the way `avgDurationMs` averages
 * means isn't a real percentile).
 */
export interface McpServerRollup {
  server: string;
  calls: number;
  failures: number;
  /** 0..1 — `failures / calls`, recomputed from the rolled-up totals (not averaged). */
  failureRate: number;
  avgDurationMs: number;
  p95DurationMs: number;
  totalBytes: number;
  estimatedTokens: number;
  /** Distinct tools this server exposed calls for in the window. */
  toolCount: number;
}

export type McpServerRollupWithShare = McpServerRollup & { share: number };

export interface McpServerRollupsWithTotal {
  rows: McpServerRollupWithShare[];
  total: number;
}

/**
 * Aggregates the per-(server, tool) rows the backend returns into one row per server,
 * ordered by total calls descending (ties broken by server name) — matching the backend
 * aggregation's own ordering convention. `failureRate` is recomputed from the rolled-up
 * `calls`/`failures` rather than averaged across tools, so it stays exact regardless of how
 * unevenly calls are spread across a server's tools.
 */
export const rollupByServer = (rows: McpServerUsageRow[]): McpServerRollup[] => {
  const bucketsByServer = new Map<string, McpServerUsageRow[]>();
  for (const row of rows) {
    const bucket = bucketsByServer.get(row.server);
    if (bucket) {
      bucket.push(row);
    } else {
      bucketsByServer.set(row.server, [row]);
    }
  }

  const rollups: McpServerRollup[] = [...bucketsByServer.entries()].map(([server, toolRows]) => {
    const calls = toolRows.reduce((sum, row) => sum + row.calls, 0);
    const failures = toolRows.reduce((sum, row) => sum + row.failures, 0);
    const totalBytes = toolRows.reduce((sum, row) => sum + row.totalBytes, 0);
    const estimatedTokens = toolRows.reduce((sum, row) => sum + row.estimatedTokens, 0);
    const weightedDurationMs = toolRows.reduce(
      (sum, row) => sum + row.avgDurationMs * row.calls,
      0,
    );
    const p95DurationMs = toolRows.reduce((max, row) => Math.max(max, row.p95DurationMs), 0);

    return {
      server,
      calls,
      failures,
      failureRate: calls === 0 ? 0 : failures / calls,
      avgDurationMs: calls === 0 ? 0 : weightedDurationMs / calls,
      p95DurationMs,
      totalBytes,
      estimatedTokens,
      toolCount: toolRows.length,
    };
  });

  return rollups.sort((left, right) => {
    const callsOrder = right.calls - left.calls;
    return callsOrder !== 0 ? callsOrder : left.server.localeCompare(right.server);
  });
};

/**
 * Attaches a `share` (0-100, percentage of total calls across every server) to each rollup
 * row. Mirrors `SkillsAgentsPage`'s `withShare` signature/shape (same total-zero guard), but
 * operates on the already-rolled-up server rows rather than the raw `IdentifierUsageRow[]`.
 */
export const withShare = (rows: McpServerRollup[]): McpServerRollupsWithTotal => {
  const total = rows.reduce((sum, row) => sum + row.calls, 0);
  const enriched: McpServerRollupWithShare[] = rows.map((row) => ({
    ...row,
    share: total === 0 ? 0 : (100 * row.calls) / total,
  }));
  return { rows: enriched, total };
};

/**
 * Stable palette index per server, ordered by total calls descending (ties by server name) —
 * the same tie-break `rollupByServer` uses, so the color order matches the rollup's own
 * default order. Unlike `SkillsAgentsPage`'s `buildModelColorIndexes`, there is no fixed
 * "known family" set to pin first — MCP servers are open-ended (whatever the user configures),
 * so every server is ordered purely by volume.
 */
export const buildServerColorIndexes = (rows: McpServerRollup[]): Map<string, number> => {
  const ordered = [...rows].sort((left, right) => {
    const callsOrder = right.calls - left.calls;
    return callsOrder !== 0 ? callsOrder : left.server.localeCompare(right.server);
  });
  return new Map(ordered.map((row, index) => [row.server, index]));
};
