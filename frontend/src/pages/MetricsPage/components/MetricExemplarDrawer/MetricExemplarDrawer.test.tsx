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
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../../test/renderWithProviders';
import type { SpanRow, TraceRow } from '../../../../api';
import { formatDistributionValue } from '../MetricDistributionCard/distributionScatter';
import MetricExemplarDrawer, { type MetricExemplar } from './MetricExemplarDrawer';

// A stat's value sits in the sibling box under its label (the waterfall repeats some figures).
const statValue = (label: string): HTMLElement =>
  screen.getByText(label).nextElementSibling as HTMLElement;

const TRACE_START_MS = Date.parse('2026-09-19T08:12:40.000Z');

const makeSpan = (
  spanId: string,
  parentSpanId: string | null,
  name: string,
  offsetMs: number,
  durationMs: number,
  overrides: Partial<SpanRow> = {},
): SpanRow => ({
  id: 0,
  spanId,
  parentSpanId,
  traceId: 'a13f9c7e2',
  name,
  kind: 'internal',
  startTimestamp: new Date(TRACE_START_MS + offsetMs).toISOString(),
  endTimestamp: new Date(TRACE_START_MS + offsetMs + durationMs).toISOString(),
  durationNanos: durationMs * 1e6,
  statusCode: 'ok',
  statusMessage: null,
  scopeName: null,
  attributes: null,
  events: null,
  resourceAttributes: null,
  ...overrides,
});

const spans: SpanRow[] = [
  makeSpan('root', null, 'claude_code.interaction', 0, 1900),
  makeSpan('call', 'root', 'claude_code.llm_request', 80, 1500, { attributes: { model: 'claude-sonnet-4' } }),
  makeSpan('edit', 'root', 'claude_code.tool', 1640, 200),
];

const summary: TraceRow = {
  traceId: 'a13f9c7e2',
  startTimestamp: '2026-09-19T08:12:40Z',
  rootSpanName: 'claude_code.interaction',
  rootSpanId: 'root',
  sessionId: 'session-77',
  spanCount: 3,
  durationNanos: 1.9e9,
  errorCount: 0,
  totalTokens: 13180,
  totalCostUsd: 0.42,
  firstUserPrompt: null,
  inProgress: false,
};

const loadedExemplar: MetricExemplar = {
  traceId: 'a13f9c7e2',
  metricName: 'claude_code.token.usage',
  unit: 'tokens',
  value: 13180,
  timestamp: '2026-09-19T08:12:40.000Z',
  summary,
  spans,
  isLoading: false,
  errorMessage: null,
};

const renderDrawer = (
  exemplar: MetricExemplar | null,
  handlers: { onClose?: () => void; onOpenInTraces?: (traceId: string, spanId: string | null) => void } = {},
) =>
  renderWithProviders(
    <MetricExemplarDrawer
      exemplar={exemplar}
      onClose={handlers.onClose ?? vi.fn()}
      onOpenInTraces={handlers.onOpenInTraces ?? vi.fn()}
    />,
  );

describe('MetricExemplarDrawer', () => {
  it('renders nothing while there is no exemplar', () => {
    renderDrawer(null);

    expect(screen.queryByText('Exemplar → Trace')).not.toBeInTheDocument();
  });

  it('names the trace, the metric and when the request was recorded', () => {
    renderDrawer(loadedExemplar);

    expect(screen.getByText('Exemplar → Trace')).toBeInTheDocument();
    expect(screen.getByText('trace a13f9c7e2')).toBeInTheDocument();
    expect(screen.getByText('claude_code.token.usage', { selector: 'b' })).toBeInTheDocument();
    expect(screen.getByText(/recorded/)).toBeInTheDocument();
  });

  it('shows the headline stat row from the point and the loaded trace', () => {
    renderDrawer(loadedExemplar);

    expect(statValue('Tokens')).toHaveTextContent(formatDistributionValue(13180, 'tokens'));
    expect(statValue('Duration')).toHaveTextContent('1.90 s');
    expect(statValue('Spans')).toHaveTextContent('3');
    expect(statValue('Model')).toHaveTextContent('Sonnet 4');
    expect(within(statValue('Status')).getByText('ok')).toBeInTheDocument();
  });

  it('labels a cost exemplar as cost', () => {
    renderDrawer({ ...loadedExemplar, metricName: 'claude_code.cost.usage', unit: 'USD', value: 0.42 });

    expect(statValue('Cost')).toHaveTextContent(formatDistributionValue(0.42, 'USD'));
  });

  it('draws the trace as a compact span waterfall', () => {
    renderDrawer(loadedExemplar);

    expect(screen.getByText('Span waterfall')).toBeInTheDocument();
    expect(screen.getByTitle('claude_code.interaction')).toBeInTheDocument();
    expect(screen.getByTitle('claude_code.llm_request')).toBeInTheDocument();
    expect(screen.getByTitle('claude_code.tool')).toBeInTheDocument();
  });

  it('leaves out the call-number badge, which only means something to the trace analysis', () => {
    renderDrawer({
      ...loadedExemplar,
      spans: spans.map((span, index) => ({ ...span, callNumber: index === 0 ? null : index })),
    });

    expect(screen.getByTitle('claude_code.llm_request')).toBeInTheDocument();
    expect(screen.queryByText(/^call \d+$/)).not.toBeInTheDocument();
  });

  it('lists what is known about the exemplar as attributes', () => {
    renderDrawer(loadedExemplar);

    expect(screen.getByText('Exemplar attributes')).toBeInTheDocument();
    expect(screen.getByText(/^session\.id/)).toBeInTheDocument();
    expect(screen.getByText('session-77')).toBeInTheDocument();
    expect(screen.getByText(/^request\.tokens/)).toBeInTheDocument();
  });

  it('marks an errored trace', () => {
    renderDrawer({ ...loadedExemplar, summary: { ...summary, errorCount: 2 } });

    expect(within(statValue('Status')).getByText('error')).toBeInTheDocument();
  });

  it('shows a loading note in place of the waterfall, with dashes for the trace figures', () => {
    renderDrawer({ ...loadedExemplar, summary: undefined, spans: undefined, isLoading: true });

    expect(screen.getByText('Loading trace…')).toBeInTheDocument();
    expect(statValue('Tokens')).toHaveTextContent(formatDistributionValue(13180, 'tokens'));
    expect(statValue('Duration')).toHaveTextContent('—');
  });

  it('shows the error message instead of the waterfall', () => {
    renderDrawer({ ...loadedExemplar, spans: undefined, errorMessage: 'trace request failed' });

    expect(screen.getByRole('alert')).toHaveTextContent('trace request failed');
    expect(screen.queryByText('Loading trace…')).not.toBeInTheDocument();
  });

  it('says so when the trace has no spans', () => {
    renderDrawer({ ...loadedExemplar, summary: null, spans: [] });

    expect(screen.getByText('No spans are recorded for this trace.')).toBeInTheDocument();
  });

  it('closes from the close button', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderDrawer(loadedExemplar, { onClose });

    await user.click(screen.getByRole('button', { name: 'Close exemplar trace' }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on Escape', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderDrawer(loadedExemplar, { onClose });

    await user.keyboard('{Escape}');

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('hands off to the full trace page with the exemplar trace id', async () => {
    const user = userEvent.setup();
    const onOpenInTraces = vi.fn();
    renderDrawer(loadedExemplar, { onOpenInTraces });

    await user.click(screen.getByRole('button', { name: /Open in Traces/ }));

    expect(onOpenInTraces).toHaveBeenCalledWith('a13f9c7e2', null);
  });

  it('hands off the exemplar span id too, so the trace page can land on that span', async () => {
    const user = userEvent.setup();
    const onOpenInTraces = vi.fn();
    renderDrawer({ ...loadedExemplar, spanId: '00f067aa0ba902b7' }, { onOpenInTraces });

    await user.click(screen.getByRole('button', { name: /Open in Traces/ }));

    expect(onOpenInTraces).toHaveBeenCalledWith('a13f9c7e2', '00f067aa0ba902b7');
  });
});
