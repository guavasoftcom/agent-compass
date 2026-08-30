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
import SectionLayout, { type SectionTab } from '../../components/SectionLayout';

const TABS: readonly SectionTab[] = [
  { to: '/tools/calls', label: 'Calls' },
  { to: '/tools/reliability', label: 'Reliability' },
  { to: '/tools/skills-agents', label: 'Skills & Subagents' },
  { to: '/tools/mcp-servers', label: 'MCP Servers' },
  { to: '/tools/permissions', label: 'Denials' },
];

// React Query key prefixes used by the five child pages. Drives the section-level
// Refresh button and the polling indicator.
const QUERY_KEY_PREFIXES: readonly string[] = [
  'tool-calls',
  'tool-calls-timeseries',
  'tool-calls-latency',
  'tool-repeats',
  'tool-failure-rates',
  'skill-usage',
  'subagent-usage',
  'mcp-usage',
  'tool-denials',
  'hook-executions',
];

const ToolActivitySection = () => {
  return (
    <SectionLayout
      title="Tool Usage"
      subtitle="How the agent uses tools, where it fails, and which skills and subagents it pulls in."
      tabs={TABS}
      queryKeyPrefixes={QUERY_KEY_PREFIXES}
    />
  );
};

export default ToolActivitySection;
