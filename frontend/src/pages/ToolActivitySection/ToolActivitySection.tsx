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
