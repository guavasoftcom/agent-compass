import { describe, expect, it } from 'vitest';
import type { LogRow, SpanRow } from '../../api';
import { costOfSelectedSpan, costOfSpan, costOfSpanRequests } from './spanCost';

const span = (costUsd?: number | null, name = 'claude_code.llm_request'): SpanRow =>
  ({ spanId: 'a1', name, costUsd } as SpanRow);

const requestLog = (costUsd: unknown, eventName = 'api_request'): LogRow =>
  ({
    id: 1,
    attributes: { 'event.name': eventName, cost_usd: costUsd },
  } as unknown as LogRow);

describe('costOfSpan', () => {
  it('reads the span_costs figure the backend filled in', () => {
    expect(costOfSpan(span(0.17))).toBe(0.17);
  });

  it('treats a missing or non-finite cost as 0 rather than NaN', () => {
    expect(costOfSpan(span(null))).toBe(0);
    expect(costOfSpan(span(undefined))).toBe(0);
    expect(costOfSpan(span(Number.NaN))).toBe(0);
  });
});

describe('costOfSpanRequests', () => {
  it('sums cost_usd across the span api_request logs', () => {
    expect(costOfSpanRequests([requestLog(0.07), requestLog(0.1)])).toBeCloseTo(0.17, 10);
  });

  it('ignores logs that are not api_request events', () => {
    expect(costOfSpanRequests([requestLog(0.07, 'api_request_body')])).toBe(0);
  });

  it('ignores an api_request log whose cost is absent or not a number', () => {
    expect(costOfSpanRequests([requestLog(undefined), requestLog('0.07')])).toBe(0);
  });

  it('returns 0 for a span with no logs at all', () => {
    expect(costOfSpanRequests(undefined)).toBe(0);
    expect(costOfSpanRequests([])).toBe(0);
  });
});

describe('costOfSelectedSpan', () => {
  // The llm_request case this exists for: Claude Code stamps the cost on the
  // span that was active (the interaction root), so the call's own span carries
  // no span_costs row and would otherwise read as free.
  it('falls back to the span own request logs when nothing was stamped against it', () => {
    expect(costOfSelectedSpan(span(0), [requestLog(0.0707685)])).toBe(0.0707685);
  });

  // A tool.execution span is both stamped AND holds its own request logs.
  // Adding the two would double-count, so the stamped figure wins outright.
  it('prefers the stamped figure and never adds the two together', () => {
    expect(costOfSelectedSpan(span(0.36), [requestLog(0.36)])).toBe(0.36);
  });

  it('is 0 when the span was neither stamped nor issued a logged request', () => {
    expect(costOfSelectedSpan(span(null), [])).toBe(0);
  });

  // A tool.execution running a subagent keeps its rollup — that figure answers
  // "what did this Agent call cost", which nothing else on the page states.
  it('reports the stamped rollup on a tool.execution span', () => {
    expect(costOfSelectedSpan(span(2.4, 'claude_code.tool.execution'), [])).toBe(2.4);
  });

  // The interaction root's rollup is the whole turn, i.e. the trace total the
  // header KPI already shows, so both per-span surfaces suppress it.
  it('reports 0 for an interaction span however its cost was resolved', () => {
    expect(costOfSelectedSpan(span(0.17, 'claude_code.interaction'), [])).toBe(0);
    expect(costOfSelectedSpan(span(0, 'claude_code.interaction'), [requestLog(0.07)])).toBe(0);
    expect(costOfSelectedSpan(span(0.17, 'CLAUDE_CODE.Interaction'), [])).toBe(0);
  });

  // Only the exact operation is suppressed — a differently-named span that
  // merely starts with the same word keeps its figure.
  it('does not suppress spans whose name only starts with interaction', () => {
    expect(costOfSelectedSpan(span(0.17, 'claude_code.interaction.turn'), [])).toBe(0.17);
  });
});
