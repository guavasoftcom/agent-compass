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
import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../../test/renderWithProviders';
import TraceSummaryInlineView, {
  type TraceSummaryInlineViewProps,
  type TraceSummaryModel,
} from './TraceSummaryInlineView';
import type { TraceRow } from '../../../../api';

const trace: TraceRow = {
  traceId: 'c'.repeat(32),
  startTimestamp: '2026-08-30T10:00:00.000Z',
  rootSpanName: 'session.turn',
  rootSpanId: 'span-1',
  sessionId: 'sess_abc123',
  spanCount: 12,
  durationNanos: 3_200_000_000,
  errorCount: 1,
  totalTokens: 42000,
  totalCostUsd: 0.83,
  firstUserPrompt: 'Refactor the theme overlay',
};

const model: TraceSummaryModel = {
  totalMs: 3200,
  shownOperations: [
    { name: 'model.completion', selfTimeMs: 1800, count: 3, errorCount: 0 },
    { name: 'tool.execute', selfTimeMs: 900, count: 2, errorCount: 1 },
  ],
  opCount: 2,
  tokenTotals: {
    input: 3000,
    output: 1200,
    cacheRead: 36000,
    cacheCreate: 1800,
    total: 42000,
  },
  calls: 3,
  maxDepth: 4,
  toolCalls: 2,
};

const baseProps: TraceSummaryInlineViewProps = {
  trace,
  model,
  isLoading: false,
  serviceHue: '#7C6CF5',
  callLabel: '3 model calls',
  onOpenTrace: vi.fn(),
};

describe('TraceSummaryInlineView', () => {
  it('renders KPI tiles, token composition, and operation breakdown from the model', () => {
    renderWithProviders(<TraceSummaryInlineView {...baseProps} />);

    expect(screen.getByText('Spans')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('model.completion')).toBeInTheDocument();
    expect(screen.getByText('tool.execute')).toBeInTheDocument();
    expect(screen.getByText('3 model calls')).toBeInTheDocument();
    expect(screen.getByText('Open full trace')).toBeInTheDocument();
  });

  it('shows a loading indicator while the span summary query is in flight', () => {
    renderWithProviders(
      <TraceSummaryInlineView {...baseProps} model={null} isLoading />,
    );

    expect(screen.getByText('Loading span summary…')).toBeInTheDocument();
  });

  it('shows the no-model-tokens message when the trace made no model calls', () => {
    const noTokenModel: TraceSummaryModel = {
      ...model,
      tokenTotals: { input: 0, output: 0, cacheRead: 0, cacheCreate: 0, total: 0 },
    };
    renderWithProviders(
      <TraceSummaryInlineView {...baseProps} model={noTokenModel} />,
    );

    expect(
      screen.getByText('No model tokens — this trace made no model calls.'),
    ).toBeInTheDocument();
  });

  it('calls onOpenTrace when "Open full trace" is clicked', async () => {
    const user = userEvent.setup();
    const onOpenTrace = vi.fn();
    renderWithProviders(
      <TraceSummaryInlineView {...baseProps} onOpenTrace={onOpenTrace} />,
    );

    await user.click(screen.getByText('Open full trace'));

    expect(onOpenTrace).toHaveBeenCalledTimes(1);
  });
});
