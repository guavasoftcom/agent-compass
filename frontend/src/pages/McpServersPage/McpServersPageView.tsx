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
import {
  Box,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import PageLayout from '../../components/PageLayout';
import StatCard from '../../components/StatCard';
import DonutCard from '../../components/DonutCard';
import BreakdownList, { type BreakdownRow } from '../../components/BreakdownList';
import { colorForIndex } from '../../theme/theme';
import { formatBytes, formatCompact } from '../../lib/format';
import type { McpServerUsageRow } from '../../api';
import {
  SLOW_P95_MS,
  UNKNOWN_SERVER,
  type McpServerRollupWithShare,
} from './mcpDerivations';

export interface McpServersPageViewProps {
  toolRows: McpServerUsageRow[];
  servers: McpServerRollupWithShare[];
  totalCalls: number;
  totalFailures: number;
  totalContextBytes: number;
  totalEstimatedTokens: number;
  slowestServer: McpServerRollupWithShare | null;
  serverColorIndexes: Map<string, number>;
  isLoading: boolean;
  error: Error | null;
}

const formatPercent = (ratio: number): string => `${(ratio * 100).toFixed(1)}%`;

const formatDurationMs = (ms: number): string =>
  ms < 1000 ? `${Math.round(ms).toLocaleString()} ms` : `${(ms / 1000).toFixed(1)} s`;

const headSx = {
  typography: 'eyebrowSm',
  color: 'text.secondary',
  borderColor: 'divider',
} as const;

/** One `(server, tool)` row's cells — shared by the main table and the collapsed long-tail table
 * below it, so a reader can expand the disclosure and still see every column, not just the tool
 * name and call count. */
const ToolDetailTableRow = ({ row }: { row: McpServerUsageRow }) => (
  <TableRow>
    <TableCell
      sx={{
        fontWeight: 600,
        ...(row.server === UNKNOWN_SERVER && {
          fontStyle: 'italic',
          color: 'text.disabled',
        }),
      }}
    >
      {row.server}
    </TableCell>
    <TableCell sx={{ fontFamily: 'monospace', fontSize: 13 }}>{row.tool}</TableCell>
    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
      {row.calls.toLocaleString()}
    </TableCell>
    <TableCell
      align="right"
      sx={{
        fontVariantNumeric: 'tabular-nums',
        color: row.failures > 0 ? 'warning.main' : 'text.secondary',
      }}
    >
      {formatPercent(row.failureRate)}
    </TableCell>
    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
      {formatDurationMs(row.avgDurationMs)}
    </TableCell>
    <TableCell
      align="right"
      sx={{
        fontVariantNumeric: 'tabular-nums',
        ...(row.p95DurationMs >= SLOW_P95_MS && {
          fontWeight: 600,
          color: 'warning.main',
        }),
      }}
    >
      {formatDurationMs(row.p95DurationMs)}
    </TableCell>
    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
      {formatBytes(row.totalBytes)}
    </TableCell>
    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums', color: 'text.secondary' }}>
      {formatCompact(row.estimatedTokens)}
    </TableCell>
  </TableRow>
);

const toolDetailTableSx = {
  '& td, & th': { borderColor: 'divider' },
  '& tbody tr:last-of-type td': { border: 0 },
  '& tbody tr': { transition: 'background-color 120ms' },
  '& tbody tr:hover': { backgroundColor: 'action.hover' },
} as const;

const ToolDetailTableHead = () => (
  <TableHead>
    <TableRow>
      <TableCell sx={headSx}>Server</TableCell>
      <TableCell sx={headSx}>Tool</TableCell>
      <TableCell align="right" sx={headSx}>Calls</TableCell>
      <TableCell align="right" sx={headSx}>Failure rate</TableCell>
      <TableCell align="right" sx={headSx}>Avg</TableCell>
      <TableCell align="right" sx={headSx}>P95</TableCell>
      <TableCell align="right" sx={headSx}>Context bytes</TableCell>
      <TableCell align="right" sx={headSx}>Est. tokens</TableCell>
    </TableRow>
  </TableHead>
);

/** "Servers" ranking — one bar per server, dot-colored to match the mix donut. */
const ServerRankingCard = ({
  servers,
  serverColorIndexes,
  isLoading,
}: {
  servers: McpServerRollupWithShare[];
  serverColorIndexes: Map<string, number>;
  isLoading: boolean;
}) => {
  const hasData = servers.length > 0;
  const rows: BreakdownRow[] = servers.map((server) => ({
    label: server.server,
    value: server.calls.toLocaleString(),
    percentage: server.share,
    colorIndex: serverColorIndexes.get(server.server) ?? 0,
    secondaryText:
      `${formatPercent(server.failureRate)} failure rate · p95 ${formatDurationMs(server.p95DurationMs)}`
      + ` · ${formatBytes(server.totalBytes)} context · ${server.toolCount} tool`
      + `${server.toolCount === 1 ? '' : 's'}`,
  }));

  return (
    <Paper variant="outlined" sx={{ p: 2, height: '100%' }}>
      <Typography variant="subtitle1" gutterBottom>
        Servers
      </Typography>
      {!hasData && !isLoading ? (
        <Typography color="text.secondary">No MCP calls in this window.</Typography>
      ) : (
        <BreakdownList rows={rows} layout="stacked" showColorDot />
      )}
    </Paper>
  );
};

/** Per-(server, tool) detail table — the drill-down the server rollup above can't show. */
const McpToolDetailTable = ({
  toolRows,
  isLoading,
}: {
  toolRows: McpServerUsageRow[];
  isLoading: boolean;
}) => {
  const hasData = toolRows.length > 0;
  const sortedRows = [...toolRows].sort((left, right) => right.calls - left.calls);

  return (
    <Paper variant="outlined" sx={{ p: 2.5 }}>
      <Typography variant="subtitle1" gutterBottom>
        Tools by server
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5, maxWidth: 760 }}>
        Every server/tool pair the agent called in this window — the detail behind the server
        rollup above. A server worth keeping should show low failure rates and modest p95
        execution and context bytes relative to how often it's actually called.
      </Typography>
      {!hasData && !isLoading ? (
        <Typography color="text.secondary">No MCP calls in this window.</Typography>
      ) : (
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small" sx={toolDetailTableSx}>
            <ToolDetailTableHead />
            <TableBody>
              {sortedRows.map((row) => (
                <ToolDetailTableRow key={`${row.server}::${row.tool}`} row={row} />
              ))}
            </TableBody>
          </Table>
        </Box>
      )}
    </Paper>
  );
};

const toSlices = (servers: McpServerRollupWithShare[], serverColorIndexes: Map<string, number>) =>
  servers.map((server) => ({
    label: server.server,
    value: server.calls,
    color: colorForIndex(serverColorIndexes.get(server.server) ?? 0),
    muted: server.server === UNKNOWN_SERVER,
  }));

const McpServersPageView = ({
  toolRows,
  servers,
  totalCalls,
  totalFailures,
  totalContextBytes,
  totalEstimatedTokens,
  slowestServer,
  serverColorIndexes,
  isLoading,
  error,
}: McpServersPageViewProps) => {
  const overallFailureRate = totalCalls === 0 ? 0 : totalFailures / totalCalls;

  return (
    <PageLayout
      subtitle={
        'MCP tool calls over the selected window, rolled up per server so you can weigh what '
        + 'a server costs (failures, latency, context bytes) against how often it actually '
        + 'earns its keep. MCP has no model dimension, unlike Skills & Subagents.'
      }
      error={error}
    >
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, 1fr)' },
          gap: 2,
        }}
      >
        <StatCard
          label="MCP calls"
          value={totalCalls.toLocaleString()}
          sub={
            <>
              across{' '}
              <Box component="span" sx={{ color: 'primary.main', fontWeight: 600 }}>
                {servers.length}
              </Box>{' '}
              server{servers.length === 1 ? '' : 's'}
            </>
          }
        />
        <StatCard
          label="Failure rate"
          value={totalCalls === 0 ? '—' : formatPercent(overallFailureRate)}
          accent={totalFailures > 0}
          sub={
            totalFailures > 0 ? (
              <>
                <Box component="span" sx={{ color: 'primary.main', fontWeight: 600 }}>
                  {totalFailures.toLocaleString()}
                </Box>{' '}
                failures across all servers
              </>
            ) : undefined
          }
        />
        <StatCard
          label="Slowest server (p95)"
          value={slowestServer ? formatDurationMs(slowestServer.p95DurationMs) : '—'}
          sub={slowestServer ? <>{slowestServer.server} execution</> : undefined}
        />
        <StatCard
          label="Context consumed"
          value={formatBytes(totalContextBytes)}
          sub={<>≈ {formatCompact(totalEstimatedTokens)} est. tokens</>}
        />
      </Box>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
          gap: 2,
          alignItems: 'stretch',
        }}
      >
        <DonutCard
          title="Server mix"
          slices={toSlices(servers, serverColorIndexes)}
          ranked
          centerValue={totalCalls.toLocaleString()}
          centerLabel="MCP calls"
          hasData={servers.length > 0}
          isLoading={isLoading}
          emptyLabel="No MCP calls in this window."
        />
        <ServerRankingCard
          servers={servers}
          serverColorIndexes={serverColorIndexes}
          isLoading={isLoading}
        />
      </Box>

      <McpToolDetailTable toolRows={toolRows} isLoading={isLoading} />
    </PageLayout>
  );
};

export default McpServersPageView;
