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
import PermissionDenialsPageView, {
  type PermissionDenialsPageViewProps,
} from './PermissionDenialsPageView';
import type { HookExecutionRow, ToolDenialRow } from '../../api';

const denialRows: ToolDenialRow[] = [
  { tool: 'Bash', source: 'user_reject', count: 12 },
  { tool: 'Bash', source: 'hook', count: 3 },
  { tool: 'Edit', source: 'config', count: 5 },
];

const hookRows: HookExecutionRow[] = [
  {
    hookEvent: 'PreToolUse',
    hookName: 'lint-guard',
    total: 20,
    successes: 15,
    blockingErrors: 4,
    nonBlockingErrors: 1,
    cancelled: 0,
  },
];

const baseProps: PermissionDenialsPageViewProps = {
  denialRows,
  hookRows,
  totalDenials: 20,
  distinctDeniedTools: 2,
  totalBlockingErrors: 4,
  mostDeniedTool: 'Bash',
  isDenialsLoading: false,
  isHooksLoading: false,
  error: null,
};

describe('PermissionDenialsPageView', () => {
  it('renders KPI tiles and per-tool detail from props', () => {
    renderWithProviders(<PermissionDenialsPageView {...baseProps} />);

    expect(screen.getByText('Total denials')).toBeInTheDocument();
    expect(screen.getAllByText('20').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Bash').length).toBeGreaterThan(0);
    expect(screen.getByText('lint-guard')).toBeInTheDocument();
  });

  it('shows an empty state for the denials donut when there are no denials', () => {
    renderWithProviders(
      <PermissionDenialsPageView
        {...baseProps}
        denialRows={[]}
        totalDenials={0}
        distinctDeniedTools={0}
        mostDeniedTool="—"
      />,
    );

    expect(screen.getAllByText('No tool denials in this window.').length).toBeGreaterThan(0);
  });

  it('surfaces the PageLayout error slot when a query has failed', () => {
    renderWithProviders(<PermissionDenialsPageView {...baseProps} error={new Error('denials query failed')} />);

    expect(screen.getByText('denials query failed')).toBeInTheDocument();
  });
});
