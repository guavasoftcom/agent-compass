import { useMemo } from 'react';
import type { SpanRow } from '../../../../api';
import { tokenBreakdownForSpan, type TokenBreakdown } from '../../../TracesPage/tokenBreakdown';
import { isToolCallSpan } from '../../../TracesPage/traceDerivations';
import type { OpGroup } from './SummaryStrip';
import TraceDetailHeaderView from './TraceDetailHeaderView';

interface Props {
  traceId: string;
  sessionId: string | null;
  spans: SpanRow[];
  rootName: string;
  earliestStartMs: number;
  totalMs: number;
  errorCount: number;
  // The page's `buildSpanDepths` memo (ancestor count per span, 0 for roots).
  // Reused here instead of a second depth walker so the KPI's Depth tile and
  // the waterfall's indentation always agree, including on orphan spans.
  depthBySpanId: Map<string, number>;
  // The page's `selfTimeNanosBySpanId` memo (own duration minus the union of
  // children's wall-clock intervals) — reused for the Time by operation
  // breakdown so it always agrees with the per-span "self time" bar shown in
  // the span inspector dock, instead of a second, cruder self-time pass.
  selfTimeNanosBySpanId: Map<string, number>;
  // Backend-authoritative trace cost from the `['trace-summary', traceId]`
  // query (`TraceRow.totalCostUsd`) — the same total the Traces list Cost
  // column shows. Null while that query hasn't resolved yet, or when it
  // resolved with no cost; the Cost KPI renders the same "no cost" placeholder
  // either way. Deliberately NOT re-derived by summing per-span costs here:
  // spans and cost-bearing request logs arrive over separate OTLP endpoints,
  // so a client-side sum can land on a different number than the list.
  traceCostUsd: number | null;
  // Aurora sync: TraceRow.firstUserPrompt (pending backend field) — see
  // TraceDetailHeaderView's doc comment.
  firstUserPrompt?: string | null;
}

interface HeaderAggregates {
  serviceLabels: string[];
  tokenBreakdown: TokenBreakdown;
  modelCallCount: number;
  toolCallCount: number;
}

const OP_TOP_COUNT = 6;

// Container: aggregates the per-span summary data (unique services, the
// four-way token breakdown, model- and tool-call counts, and the Time by
// operation self-time groups) in a single memoized pass over `spans`, and
// hands plain values to the presentational view. Cost and max depth are
// threaded down rather than recomputed here — see the `traceCostUsd` /
// `depthBySpanId` prop docs above.
const TraceDetailHeader = ({
  traceId,
  sessionId,
  spans,
  rootName,
  earliestStartMs,
  totalMs,
  errorCount,
  depthBySpanId,
  selfTimeNanosBySpanId,
  traceCostUsd,
  firstUserPrompt,
}: Props) => {
  const aggregates = useMemo<HeaderAggregates>(() => {
    const serviceLabels = new Set<string>();
    const tokenBreakdown: TokenBreakdown = {
      input: 0,
      output: 0,
      cacheCreate: 0,
      cacheRead: 0,
      total: 0,
    };
    let modelCallCount = 0;
    let toolCallCount = 0;
    for (const span of spans) {
      serviceLabels.add((span.scopeName ?? '—').replace('claude_code.', ''));
      // A span "made a model call" exactly when it carries token attributes —
      // derived from the same pass that sums the four-way breakdown, rather
      // than a second `tokenBreakdownForSpan` pass just to test `.total > 0`.
      const spanTokens = tokenBreakdownForSpan(span);
      tokenBreakdown.input += spanTokens.input;
      tokenBreakdown.output += spanTokens.output;
      tokenBreakdown.cacheCreate += spanTokens.cacheCreate;
      tokenBreakdown.cacheRead += spanTokens.cacheRead;
      tokenBreakdown.total += spanTokens.total;
      if (spanTokens.total > 0) {
        modelCallCount += 1;
      }
      if (isToolCallSpan(span.name)) {
        toolCallCount += 1;
      }
    }
    return {
      serviceLabels: [...serviceLabels],
      tokenBreakdown,
      modelCallCount,
      toolCallCount,
    };
  }, [spans]);

  // Depth of the deepest span, 1-indexed (a trace with only a root span is
  // depth 1) — derived from the page's shared `depthBySpanId` map so there is
  // one depth algorithm for the whole page.
  const maximumDepth = useMemo(() => {
    let deepestAncestorCount = -1;
    for (const ancestorCount of depthBySpanId.values()) {
      if (ancestorCount > deepestAncestorCount) {
        deepestAncestorCount = ancestorCount;
      }
    }
    return deepestAncestorCount < 0 ? 0 : deepestAncestorCount + 1;
  }, [depthBySpanId]);

  // Time by operation: self-time grouped by exact span name — no folding of
  // sub-spans into their parent's bucket. `claude_code.tool`,
  // `claude_code.tool.execution`, and `claude_code.tool.blocked_on_user` stay
  // three separate rows, same as the Traces list's inline expand, because
  // that's the granularity a reader deciding "where did the time go" wants —
  // the coarser "how many tool calls happened" question is what the Tool
  // calls KPI (which does fold sub-spans via `isToolCallSpan`) answers.
  const opBreakdown = useMemo(() => {
    const groups = new Map<string, OpGroup>();
    for (const span of spans) {
      const group = groups.get(span.name) ?? {
        name: span.name,
        selfTimeMs: 0,
        count: 0,
        errorCount: 0,
      };
      group.selfTimeMs += (selfTimeNanosBySpanId.get(span.spanId) ?? 0) / 1e6;
      group.count += 1;
      if (span.statusCode === 'error') {
        group.errorCount += 1;
      }
      groups.set(span.name, group);
    }
    const operations = [...groups.values()].sort(
      (a, b) => b.selfTimeMs - a.selfTimeMs,
    );
    const shownOperations = operations.slice(0, OP_TOP_COUNT);
    const remainingOperations = operations.slice(OP_TOP_COUNT);
    if (remainingOperations.length) {
      shownOperations.push(
        remainingOperations.reduce<OpGroup>(
          (acc, group) => ({
            ...acc,
            selfTimeMs: acc.selfTimeMs + group.selfTimeMs,
            count: acc.count + group.count,
            errorCount: acc.errorCount + group.errorCount,
          }),
          {
            name: `other · ${remainingOperations.length} ops`,
            selfTimeMs: 0,
            count: 0,
            errorCount: 0,
            other: true,
          },
        ),
      );
    }
    return { shownOperations, opCount: groups.size };
  }, [spans, selfTimeNanosBySpanId]);

  return (
    <TraceDetailHeaderView
      traceId={traceId}
      sessionId={sessionId}
      rootName={rootName}
      earliestStartMs={earliestStartMs}
      totalMs={totalMs}
      errorCount={errorCount}
      spanCount={spans.length}
      serviceLabels={aggregates.serviceLabels}
      tokenBreakdown={aggregates.tokenBreakdown}
      modelCallCount={aggregates.modelCallCount}
      toolCallCount={aggregates.toolCallCount}
      maximumDepth={maximumDepth}
      totalCostUsd={traceCostUsd ?? 0}
      shownOperations={opBreakdown.shownOperations}
      opCount={opBreakdown.opCount}
      firstUserPrompt={firstUserPrompt}
    />
  );
};

export default TraceDetailHeader;
