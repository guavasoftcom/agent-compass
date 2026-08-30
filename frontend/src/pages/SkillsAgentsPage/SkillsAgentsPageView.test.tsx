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
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import SkillsAgentsPageView, {
  type SkillsAgentsPageViewProps,
} from './SkillsAgentsPageView';
import type { IdentifierUsageRow } from '../../api';
import {
  buildModelColorIndexes,
  buildModelCoverageModels,
  buildModelFirstBlocks,
  withShare,
} from './skillsAgentsDerivations';

const skillUsageRows: IdentifierUsageRow[] = [
  {
    tool: 'pdf-fill',
    calls: 20,
    byModel: { 'claude-sonnet-4-5': 15, 'claude-opus-4-5': 5 },
    costUsd: 4.2,
    costByModel: { 'claude-sonnet-4-5': 3.1, 'claude-opus-4-5': 1.1 },
  },
  {
    tool: 'unknown',
    calls: 5,
    byModel: { 'claude-sonnet-4-5': 5 },
    costUsd: 0.5,
    costByModel: { 'claude-sonnet-4-5': 0.5 },
  },
];

const subagentUsageRows: IdentifierUsageRow[] = [
  {
    tool: 'code-reviewer',
    calls: 8,
    byModel: { 'claude-opus-4-5': 8 },
    costUsd: 3.0,
    costByModel: { 'claude-opus-4-5': 3.0 },
  },
];

const { rows: skillRows, total: skillTotal } = withShare(skillUsageRows);
const { rows: subagentRows, total: subagentTotal } = withShare(subagentUsageRows);
const modelColorIndexes = buildModelColorIndexes(skillUsageRows, subagentUsageRows);
const modelCoverageModels = buildModelCoverageModels(modelColorIndexes);
const skillModelBlocks = buildModelFirstBlocks(skillUsageRows, modelColorIndexes);
const subagentModelBlocks = buildModelFirstBlocks(subagentUsageRows, modelColorIndexes);

const baseProps: SkillsAgentsPageViewProps = {
  skillRows,
  skillTotal,
  isSkillsLoading: false,
  subagentRows,
  subagentTotal,
  isSubagentsLoading: false,
  modelCoverageModels,
  skillModelBlocks,
  subagentModelBlocks,
  error: null,
};

describe('SkillsAgentsPageView', () => {
  it('renders KPI tiles, mix donuts, and by-model blocks from props', () => {
    renderWithProviders(<SkillsAgentsPageView {...baseProps} />);

    expect(screen.getByText('Skill invocations')).toBeInTheDocument();
    expect(screen.getAllByText('25').length).toBeGreaterThan(0);
    expect(screen.getAllByText('pdf-fill').length).toBeGreaterThan(0);
    expect(screen.getByText('Skills by model')).toBeInTheDocument();
    expect(screen.getByText('Subagents by model')).toBeInTheDocument();
    expect(screen.getAllByText('code-reviewer').length).toBeGreaterThan(0);
  });

  it('shows empty-state messaging for skills and subagents when there is no usage', () => {
    renderWithProviders(
      <SkillsAgentsPageView
        {...baseProps}
        skillRows={[]}
        skillTotal={0}
        subagentRows={[]}
        subagentTotal={0}
        skillModelBlocks={[]}
        subagentModelBlocks={[]}
      />,
    );

    expect(screen.getAllByText('No Skill invocations in this window.').length).toBeGreaterThan(0);
    expect(screen.getAllByText('No subagent invocations in this window.').length).toBeGreaterThan(0);
  });

  it('surfaces the PageLayout error slot when a query has failed', () => {
    renderWithProviders(<SkillsAgentsPageView {...baseProps} error={new Error('skill usage query failed')} />);

    expect(screen.getByText('skill usage query failed')).toBeInTheDocument();
  });
});
