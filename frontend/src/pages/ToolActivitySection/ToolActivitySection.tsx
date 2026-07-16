import SectionLayout, { type SectionTab } from '../../components/SectionLayout';

const TABS: readonly SectionTab[] = [
  { to: '/tool-activity/calls', label: 'Calls' },
  { to: '/tool-activity/reliability', label: 'Reliability' },
  { to: '/tool-activity/skills-agents', label: 'Skills & Subagents' },
  { to: '/tool-activity/permissions', label: 'Denials' },
];

// React Query key prefixes used by the four child pages. Drives the section-level
// Refresh button and the polling indicator.
const QUERY_KEY_PREFIXES: readonly string[] = [
  'tool-calls',
  'tool-calls-timeseries',
  'tool-calls-latency',
  'tool-repeats',
  'tool-failure-rates',
  'skill-usage',
  'subagent-usage',
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
