import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  fetchSkillUsage,
  fetchSubagentUsage,
  type ToolCallRow,
} from '../../api';
import { useSectionContext } from '../../components/SectionLayout';
import SkillsAgentsPageView, {
  type IdentifierRowWithShare,
} from './SkillsAgentsPageView';

const AUTO_REFRESH_INTERVAL_MS = 60_000;

const withShare = (rows: ToolCallRow[]): {
  rows: IdentifierRowWithShare[];
  total: number;
} => {
  const total = rows.reduce((sum, row) => sum + row.calls, 0);
  const enriched: IdentifierRowWithShare[] = rows.map((row) => ({
    ...row,
    share: total === 0 ? 0 : (100 * row.calls) / total,
  }));
  return { rows: enriched, total };
};

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

  return (
    <SkillsAgentsPageView
      skillRows={skills.rows}
      skillTotal={skills.total}
      isSkillsLoading={skillsQuery.isLoading}
      subagentRows={subagents.rows}
      subagentTotal={subagents.total}
      isSubagentsLoading={subagentsQuery.isLoading}
      error={(skillsQuery.error ?? subagentsQuery.error) as Error | null}
    />
  );
}
