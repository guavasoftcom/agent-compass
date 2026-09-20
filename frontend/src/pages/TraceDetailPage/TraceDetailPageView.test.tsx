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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import TraceDetailPageView, {
  type TraceDetailPageViewProps,
} from './TraceDetailPageView';
import type { SpanRow } from '../../api';
import { NEW_SPAN_HIGHLIGHT_MS } from './spanArrivalHighlight';
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
  // The backend numbers only tool calls and model requests, so of this
  // fixture's three spans exactly one carries a call number -- which is the
  // whole reason the badge exists (see the call-number test below).
  callNumber: 1,
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
  agentColorBySpanId: new Map<string, string>(),
  agentLabelBySpanId: new Map<string, string>(),
  agentLegend: [],
  descendantErrorCounts,
  selfTimeNanosBySpanId,
  logsBySpanId,
  sessionId: 'session-abc',
  firstUserPrompt: 'Refactor the Aurora theme overlay.',
  traceCostUsd: 0.42,
  traceBackgroundCostUsd: 0,
  traceInProgress: false,
  traceAnalysis: null,
  ollamaAnalysisEnabled: true,
};

// What one more poll of a running trace brings: a fourth span, ending inside the recorded window so
// only the row count changes.
const lateSpan: SpanRow = {
  ...executionSpan,
  id: 4,
  spanId: 'span-late',
  parentSpanId: 'span-root',
  name: 'claude_code.llm_request',
  startTimestamp: '2026-08-30T10:00:00.400Z',
  statusCode: 'ok',
  statusMessage: null,
};
const grownSpans = [...spans, lateSpan];
const grownTree = buildSpanTree(grownSpans);
const grownProps: TraceDetailPageViewProps = {
  ...baseProps,
  spans: grownSpans,
  tree: grownTree,
  spanIndices: buildSpanIndices(grownTree.roots, grownTree.childrenByParentId),
  depthBySpanId: buildSpanDepths(grownTree.roots, grownTree.childrenByParentId),
  traceWindow: computeTraceWindow(grownSpans),
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

  it('badges the call number a trace-analysis citation would name, and only on a call', () => {
    // "Call 1" in a review means this row -- and specifically not the row the
    // waterfall's own index badge numbers 1, which is the interaction root.
    const { container } = renderWithProviders(
      <TraceDetailPageView {...baseProps} />,
    );

    expect(getRow(container, 'span-tool')).toHaveTextContent('call 1');
    expect(getRow(container, 'span-root')).not.toHaveTextContent('call 1');
    expect(getRow(container, 'span-exec')).not.toHaveTextContent('call');
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

  it('opens the drawer on the deep-linked span when initialSpanId names one in the trace', () => {
    renderWithProviders(<TraceDetailPageView {...baseProps} initialSpanId="span-exec" />);

    expect(screen.getByText('span id')).toBeInTheDocument();
    expect(screen.getByText('span-exec')).toBeInTheDocument();
  });

  it('waits for the spans to load before revealing the deep-linked span', () => {
    const { rerender } = renderWithProviders(
      <TraceDetailPageView {...baseProps} spans={undefined} isLoading initialSpanId="span-exec" />,
    );
    expect(screen.queryByText('span id')).not.toBeInTheDocument();

    rerender(<TraceDetailPageView {...baseProps} initialSpanId="span-exec" />);

    expect(screen.getByText('span id')).toBeInTheDocument();
    expect(screen.getByText('span-exec')).toBeInTheDocument();
  });

  it('opens nothing when the deep-linked span is not in the trace', () => {
    renderWithProviders(<TraceDetailPageView {...baseProps} initialSpanId="no-such-span" />);

    expect(screen.queryByText('span id')).not.toBeInTheDocument();
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

  it('opens the analyze trace dialog when "Analyze trace" is clicked', async () => {
    const user = userEvent.setup();
    renderWithProviders(<TraceDetailPageView {...baseProps} />);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /analyze trace/i }));

    const dialog = screen.getByRole('dialog');
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByText('trace-0102', { exact: false })).toBeInTheDocument();
  });

  it('hides the "Analyze trace" button when Ollama is disabled', () => {
    renderWithProviders(
      <TraceDetailPageView {...baseProps} ollamaAnalysisEnabled={false} />,
    );

    expect(
      screen.queryByRole('button', { name: /analyze trace/i }),
    ).not.toBeInTheDocument();
  });

  it('renders the live-tail row when the trace is in progress, and hides it otherwise', () => {
    const { rerender } = renderWithProviders(
      <TraceDetailPageView {...baseProps} traceInProgress />,
    );

    expect(screen.getByText('waiting for more spans…')).toBeInTheDocument();

    rerender(<TraceDetailPageView {...baseProps} traceInProgress={false} />);

    expect(screen.queryByText('waiting for more spans…')).not.toBeInTheDocument();
  });

  describe('following a running trace', () => {
    const ROW_HEIGHT_PX = 30;
    const VIEWPORT_HEIGHT_PX = 60;

    // jsdom does no layout, so scrollHeight/clientHeight are 0 everywhere; stand in a height that
    // grows with the rendered rows so "new spans arrived" changes what the waterfall can scroll.
    beforeEach(() => {
      Object.defineProperty(HTMLElement.prototype, 'scrollHeight', {
        configurable: true,
        get(this: HTMLElement) {
          return this.querySelectorAll('[data-span]').length * ROW_HEIGHT_PX;
        },
      });
      Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
        configurable: true,
        get: () => VIEWPORT_HEIGHT_PX,
      });
    });

    afterEach(() => {
      delete (HTMLElement.prototype as unknown as Record<string, unknown>).scrollHeight;
      delete (HTMLElement.prototype as unknown as Record<string, unknown>).clientHeight;
    });

    const scrollerOf = (container: HTMLElement): HTMLElement =>
      getRow(container, 'span-root').parentElement as HTMLElement;

    it('scrolls to the new bottom when the reader was already at the bottom', () => {
      const { container, rerender } = renderWithProviders(
        <TraceDetailPageView {...baseProps} traceInProgress />,
      );
      // Three rows of 30px in a 60px viewport: the bottom is a scrollTop of 30.
      scrollerOf(container).scrollTop = 30;

      rerender(<TraceDetailPageView {...grownProps} traceInProgress />);

      expect(scrollerOf(container).scrollTop).toBe(4 * ROW_HEIGHT_PX);
    });

    it('leaves a reader who scrolled up where they are', () => {
      const { container, rerender } = renderWithProviders(
        <TraceDetailPageView {...baseProps} traceInProgress />,
      );
      scrollerOf(container).scrollTop = 0;

      rerender(<TraceDetailPageView {...grownProps} traceInProgress />);

      expect(scrollerOf(container).scrollTop).toBe(0);
    });

    it('never scrolls a finished trace by itself', () => {
      const { container, rerender } = renderWithProviders(
        <TraceDetailPageView {...baseProps} />,
      );
      scrollerOf(container).scrollTop = 30;

      rerender(<TraceDetailPageView {...grownProps} />);

      expect(scrollerOf(container).scrollTop).toBe(30);
    });
  });

  describe('highlighting newly arrived spans', () => {
    // The flash is an `animation` on the row; jsdom keeps the shorthand text as authored.
    const isFlashing = (row: HTMLElement): boolean =>
      getComputedStyle(row).animation.includes(`${NEW_SPAN_HIGHLIGHT_MS}ms`);

    afterEach(() => {
      vi.useRealTimers();
    });

    it('flashes only a span that arrives after the first load, then lets it settle', () => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
      const { container, rerender } = renderWithProviders(
        <TraceDetailPageView {...baseProps} traceInProgress />,
      );
      // Everything on screen on arrival is the baseline, not news.
      expect(isFlashing(getRow(container, 'span-root'))).toBe(false);

      rerender(<TraceDetailPageView {...grownProps} traceInProgress />);

      expect(isFlashing(getRow(container, 'span-late'))).toBe(true);
      expect(isFlashing(getRow(container, 'span-root'))).toBe(false);

      act(() => {
        vi.advanceTimersByTime(NEW_SPAN_HIGHLIGHT_MS);
      });

      expect(isFlashing(getRow(container, 'span-late'))).toBe(false);
    });
  });

  describe('running trace window', () => {
    // The row's timeline bar is the only 13px-tall absolutely positioned box in it.
    const barWidthPercent = (row: HTMLElement): number => {
      const bar = Array.from(row.querySelectorAll<HTMLElement>('div')).find((element) => {
        const style = getComputedStyle(element);
        return style.position === 'absolute' && style.height === '13px';
      });
      if (!bar) {
        throw new Error('timeline bar not found');
      }
      return parseFloat(getComputedStyle(bar).width);
    };

    afterEach(() => {
      vi.useRealTimers();
    });

    it('stretches the window to now and narrows earlier bars on every tick', () => {
      // The fixture's spans end 500ms after the trace started; "now" is a full second in.
      vi.useFakeTimers({
        now: Date.parse('2026-08-30T10:00:01.000Z'),
        toFake: ['Date', 'setInterval', 'clearInterval'],
      });
      const { container } = renderWithProviders(
        <TraceDetailPageView {...baseProps} traceInProgress />,
      );

      expect(barWidthPercent(getRow(container, 'span-root'))).toBeCloseTo(50, 0);

      act(() => {
        vi.advanceTimersByTime(1000);
      });

      expect(barWidthPercent(getRow(container, 'span-root'))).toBeCloseTo(25, 0);
    });

    it('leaves a finished trace on the window its spans define, however late it is', () => {
      vi.useFakeTimers({
        now: Date.parse('2026-08-30T10:00:09.000Z'),
        toFake: ['Date', 'setInterval', 'clearInterval'],
      });
      const { container } = renderWithProviders(<TraceDetailPageView {...baseProps} />);

      expect(barWidthPercent(getRow(container, 'span-root'))).toBeCloseTo(100, 0);
    });
  });
});
