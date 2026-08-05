import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchSkillUsage, fetchSubagentUsage } from '../../api';
import { useSectionContext } from '../../components/SectionLayout';
import { AUTO_REFRESH_INTERVAL_MS } from '../../lib/constants';
import SkillsAgentsPageView from './SkillsAgentsPageView';
import {
  buildModelColorIndexes,
  buildModelLegendItems,
  toModelBreakdownRows,
  withShare,
} from './skillsAgentsDerivations';

export default function SkillsAgentsPage() {
  const { selection, autoRefresh } = useSectionContext();

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const refetchInterval =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  const skillsQuery = useQuery({
    queryKey: ['skill-usage', selectionKey],
    queryFn: () => fetchSkillUsage(selection),
    refetchInterval,
  });
  const subagentsQuery = useQuery({
    queryKey: ['subagent-usage', selectionKey],
    queryFn: () => fetchSubagentUsage(selection),
    refetchInterval,
  });

  const skills = useMemo(() => withShare(skillsQuery.data ?? []), [skillsQuery.data]);
  const subagents = useMemo(
    () => withShare(subagentsQuery.data ?? []),
    [subagentsQuery.data],
  );

  // One palette index per model across both cards, so a model keeps the same
  // colour whether it shows up under a skill or under a subagent.
  const modelColorIndexes = useMemo(
    () => buildModelColorIndexes(skills.rows, subagents.rows),
    [skills.rows, subagents.rows],
  );

  const skillModelRows = useMemo(
    () => toModelBreakdownRows(skills.rows, modelColorIndexes),
    [skills.rows, modelColorIndexes],
  );
  const subagentModelRows = useMemo(
    () => toModelBreakdownRows(subagents.rows, modelColorIndexes),
    [subagents.rows, modelColorIndexes],
  );
  const skillModelLegendItems = useMemo(
    () => buildModelLegendItems(skills.rows, modelColorIndexes),
    [skills.rows, modelColorIndexes],
  );
  const subagentModelLegendItems = useMemo(
    () => buildModelLegendItems(subagents.rows, modelColorIndexes),
    [subagents.rows, modelColorIndexes],
  );

  return (
    <SkillsAgentsPageView
      skillRows={skills.rows}
      skillTotal={skills.total}
      isSkillsLoading={skillsQuery.isLoading}
      subagentRows={subagents.rows}
      subagentTotal={subagents.total}
      isSubagentsLoading={subagentsQuery.isLoading}
      skillModelRows={skillModelRows}
      skillModelLegendItems={skillModelLegendItems}
      subagentModelRows={subagentModelRows}
      subagentModelLegendItems={subagentModelLegendItems}
      error={(skillsQuery.error ?? subagentsQuery.error) as Error | null}
    />
  );
}
