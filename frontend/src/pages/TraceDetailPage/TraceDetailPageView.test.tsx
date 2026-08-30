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
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import TraceDetailPageView, {
  type TraceDetailPageViewProps,
} from './TraceDetailPageView';
import type { SpanRow } from '../../api';
import {
  buildSpanDepths,
  buildSpanIndices,
  buildSpanTree,
  computeTraceWindow,
} from './spanTree';

const rootSpan: SpanRow = {
  id: 1,
  spanId: 'span-root',
  parentSpanId: null,
  traceId: 'trace-0102',
  name: 'claude_code.interaction',
  kind: 'internal',
  startTimestamp: '2026-08-30T10:00:00.000Z',
  endTimestamp: '2026-08-30T10:00:00.500Z',
  durationNanos: 500_000_000,
  statusCode: 'ok',
  statusMessage: null,
  scopeName: 'claude-code',
  attributes: {},
  events: null,
  resourceAttributes: {},
};

const toolSpan: SpanRow = {
  id: 2,
  spanId: 'span-tool',
  parentSpanId: 'span-root',
  traceId: 'trace-0102',
  name: 'claude_code.tool',
  kind: 'internal',
  startTimestamp: '2026-08-30T10:00:00.050Z',
  endTimestamp: '2026-08-30T10:00:00.350Z',
  durationNanos: 300_000_000,
  statusCode: 'ok',
  statusMessage: null,
  scopeName: 'claude-code',
  attributes: { tool_name: 'Bash', 'tool.status': 'ok', command: 'echo hi' },
  events: null,
  resourceAttributes: {},
};

const executionSpan: SpanRow = {
  id: 3,
  spanId: 'span-exec',
  parentSpanId: 'span-tool',
  traceId: 'trace-0102',
  name: 'claude_code.tool.execution',
  kind: 'internal',
  startTimestamp: '2026-08-30T10:00:00.060Z',
  endTimestamp: '2026-08-30T10:00:00.160Z',
  durationNanos: 100_000_000,
  statusCode: 'error',
  statusMessage: 'boom',
  scopeName: 'claude-code',
  attributes: {},
  events: null,
  resourceAttributes: {},
};

const spans: SpanRow[] = [rootSpan, toolSpan, executionSpan];
const tree = buildSpanTree(spans);
const spanIndices = buildSpanIndices(tree.roots, tree.childrenByParentId);
const depthBySpanId = buildSpanDepths(tree.roots, tree.childrenByParentId);
const traceWindow = computeTraceWindow(spans);

const descendantErrorCounts = new Map<string, number>([
  ['span-root', 1],
  ['span-tool', 1],
]);
const selfTimeNanosBySpanId = new Map<string, number>([
  ['span-root', 200_000_000],
  ['span-tool', 200_000_000],
  ['span-exec', 100_000_000],
]);
const logsBySpanId = new Map();

const baseProps: TraceDetailPageViewProps = {
  traceId: 'trace-0102',
  spans,
  isLoading: false,
  error: null,
  tree,
  spanIndices,
  depthBySpanId,
  traceWindow,
  collapsibleToolSpanIds: ['span-tool'],
  descendantErrorCounts,
  selfTimeNanosBySpanId,
  logsBySpanId,
  sessionId: 'session-abc',
  firstUserPrompt: 'Refactor the Aurora theme overlay.',
  traceCostUsd: 0.42,
  traceBackgroundCostUsd: 0,
};

// The fixture's span names ("claude_code.interaction" etc.) legitimately
// repeat across the page — once as a waterfall row, again in the header's
// MetaFooter (root span name) and Time-by-operation breakdown — so waterfall
// assertions go through the row's own `data-span` attribute rather than
// text queries that would otherwise match more than one element.
const getRow = (container: HTMLElement, spanId: string): HTMLElement => {
  const row = container.querySelector<HTMLElement>(`[data-span="${spanId}"]`);
  if (!row) {
    throw new Error(`row for span ${spanId} not found`);
  }
  return row;
};

describe('TraceDetailPageView', () => {
  it('renders the header and one waterfall row per span, with no drawer selection on arrival', () => {
    const { container } = renderWithProviders(
      <TraceDetailPageView {...baseProps} />,
    );

    expect(screen.getByText('Trace detail')).toBeInTheDocument();
    expect(getRow(container, 'span-root')).toHaveTextContent('claude_code.interaction');
    expect(getRow(container, 'span-tool')).toHaveTextContent('claude_code.tool');
    expect(getRow(container, 'span-exec')).toHaveTextContent('claude_code.tool.execution');
    // Nothing selected on arrival, so the drawer content isn't rendered.
    expect(screen.queryByText('span id')).not.toBeInTheDocument();
  });

  it('shows a loading indicator while isLoading is true', () => {
    renderWithProviders(
      <TraceDetailPageView {...baseProps} isLoading spans={undefined} />,
    );

    expect(screen.getByText('Loading trace…')).toBeInTheDocument();
  });

  it('shows the empty/error state with a link back to traces when there are no spans', () => {
    renderWithProviders(
      <TraceDetailPageView {...baseProps} spans={[]} isLoading={false} />,
    );

    expect(screen.getByText('Trace not found or has no spans.')).toBeInTheDocument();
    expect(screen.getByText('Back to traces')).toBeInTheDocument();
  });

  it('surfaces the query error message in the empty state', () => {
    renderWithProviders(
      <TraceDetailPageView
        {...baseProps}
        spans={undefined}
        isLoading={false}
        error={new Error('trace not found')}
      />,
    );

    expect(screen.getByText('trace not found')).toBeInTheDocument();
  });

  it('opens the inspector drawer with the selected span on row click', async () => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(
      <TraceDetailPageView {...baseProps} />,
    );

    await user.click(getRow(container, 'span-exec'));

    // The drawer renders the selected span's meta grid, unique to the drawer
    // being open.
    expect(screen.getByText('span id')).toBeInTheDocument();
    expect(screen.getByText('span-exec')).toBeInTheDocument();
  });

  it('closes the drawer when the already-selected row is clicked again', async () => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(
      <TraceDetailPageView {...baseProps} />,
    );

    const row = getRow(container, 'span-exec');
    await user.click(row);
    const drawerScroll = container.querySelector('[data-drawer-scroll]');
    expect(drawerScroll).not.toBeNull();
    expect(drawerScroll?.closest('[inert]')).toBeNull();

    // The drawer stays mounted (its content is kept during the close
    // transition — see the SpanInspectorDrawer CLAUDE.md gotcha) but is
    // marked `inert` while closed, so re-clicking the selected row is the
    // observable "closed" signal rather than the content disappearing.
    await user.click(row);
    expect(drawerScroll?.closest('[inert]')).not.toBeNull();
  });

  it('collapses the tool span subtree when "Collapse all" is pressed', async () => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(
      <TraceDetailPageView {...baseProps} />,
    );

    expect(container.querySelector('[data-span="span-exec"]')).not.toBeNull();

    const collapseAllButton = screen.getByRole('button', { name: /collapse all/i });
    await user.click(collapseAllButton);

    expect(container.querySelector('[data-span="span-exec"]')).toBeNull();
    expect(container.querySelector('[data-span="span-tool"]')).not.toBeNull();

    const expandAllButton = screen.getByRole('button', { name: /expand all/i });
    await user.click(expandAllButton);
    expect(container.querySelector('[data-span="span-exec"]')).not.toBeNull();
  });
});
