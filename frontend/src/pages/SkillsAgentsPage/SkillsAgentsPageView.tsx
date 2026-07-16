import { Box } from '@mui/material';
import PageLayout from '../../components/PageLayout';
import StatCard from '../../components/StatCard';
import DonutCard from '../../components/DonutCard';
import { colorForIndex } from '../../theme/theme';
import type { ToolCallRow } from '../../api';

export type IdentifierRowWithShare = ToolCallRow & { share: number };

export interface SkillsAgentsPageViewProps {
  skillRows: IdentifierRowWithShare[];
  skillTotal: number;
  isSkillsLoading: boolean;
  subagentRows: IdentifierRowWithShare[];
  subagentTotal: number;
  isSubagentsLoading: boolean;
  error: Error | null;
}

const UNKNOWN_BUCKET = 'unknown';

const toSlices = (rows: IdentifierRowWithShare[]) =>
  rows.map((row, index) => ({
    label: row.tool,
    value: row.calls,
    color: colorForIndex(index),
    muted: row.tool === UNKNOWN_BUCKET,
  }));

const Accent = ({ children }: { children: React.ReactNode }) => (
  <Box component="span" sx={{ color: 'primary.main', fontWeight: 600 }}>
    {children}
  </Box>
);

const SkillsAgentsPageView = ({
  skillRows,
  skillTotal,
  isSkillsLoading,
  subagentRows,
  subagentTotal,
  isSubagentsLoading,
  error,
}: SkillsAgentsPageViewProps) => {
  const topSkill = skillRows[0] ?? null;
  const topSubagent = subagentRows[0] ?? null;

  return (
    <PageLayout
      subtitle={
        'Skill and subagent invocations over the selected window. Skills that never trigger '
        + 'usually have descriptions that don\'t match what the agent looks for; subagents '
        + 'called for trivial work belong as inline tool sequences instead.'
      }
      error={error}
    >
      {/* Headline stats — Skills pair then Subagents pair */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, 1fr)' },
          gap: 2,
        }}
      >
        <StatCard
          label="Skill invocations"
          value={skillTotal.toLocaleString()}
          sub={<>across <Accent>{skillRows.length}</Accent> distinct skills</>}
        />
        <StatCard
          label="Top skill"
          value={topSkill ? topSkill.tool : '—'}
          accent={Boolean(topSkill)}
          sub={topSkill ? <><Accent>{topSkill.share.toFixed(1)}%</Accent> of skill calls</> : undefined}
        />
        <StatCard
          label="Subagent invocations"
          value={subagentTotal.toLocaleString()}
          sub={<>across <Accent>{subagentRows.length}</Accent> distinct subagents</>}
        />
        <StatCard
          label="Top subagent"
          value={topSubagent ? topSubagent.tool : '—'}
          accent={Boolean(topSubagent)}
          sub={
            topSubagent ? (
              <><Accent>{topSubagent.share.toFixed(1)}%</Accent> of subagent calls</>
            ) : undefined
          }
        />
      </Box>

      {/* Skill mix beside Subagent mix — each donut's legend carries the full breakdown */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
          gap: 2,
          alignItems: 'stretch',
        }}
      >
        <DonutCard
          title="Skill mix"
          slices={toSlices(skillRows)}
          ranked
          centerValue={skillTotal.toLocaleString()}
          centerLabel="skill calls"
          hasData={skillRows.length > 0}
          isLoading={isSkillsLoading}
          emptyLabel="No Skill invocations in this window."
        />
        <DonutCard
          title="Subagent mix"
          slices={toSlices(subagentRows)}
          ranked
          centerValue={subagentTotal.toLocaleString()}
          centerLabel="subagent calls"
          hasData={subagentRows.length > 0}
          isLoading={isSubagentsLoading}
          emptyLabel="No subagent invocations in this window."
        />
      </Box>
    </PageLayout>
  );
};

export default SkillsAgentsPageView;
