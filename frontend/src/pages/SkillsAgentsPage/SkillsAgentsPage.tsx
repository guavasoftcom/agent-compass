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
import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchSkillUsage, fetchSubagentUsage } from '../../api';
import { useSectionContext } from '../../components/SectionLayout';
import { AUTO_REFRESH_INTERVAL_MS } from '../../lib/constants';
import SkillsAgentsPageView from './SkillsAgentsPageView';
import {
  buildModelColorIndexes,
  buildModelCoverageModels,
  buildModelFirstBlocks,
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
  // colour (and the same fixed Sonnet/Opus/Haiku position) whether it shows
  // up under a skill or under a subagent.
  const modelColorIndexes = useMemo(
    () => buildModelColorIndexes(skills.rows, subagents.rows),
    [skills.rows, subagents.rows],
  );

  // Shared by both DonutCards' coverage ticks + legend caption, and implicitly
  // fixes the by-model blocks' order too.
  const modelCoverageModels = useMemo(
    () => buildModelCoverageModels(modelColorIndexes),
    [modelColorIndexes],
  );

  const skillModelBlocks = useMemo(
    () => buildModelFirstBlocks(skills.rows, modelColorIndexes),
    [skills.rows, modelColorIndexes],
  );
  const subagentModelBlocks = useMemo(
    () => buildModelFirstBlocks(subagents.rows, modelColorIndexes),
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
      modelCoverageModels={modelCoverageModels}
      skillModelBlocks={skillModelBlocks}
      subagentModelBlocks={subagentModelBlocks}
      error={(skillsQuery.error ?? subagentsQuery.error) as Error | null}
    />
  );
}
