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
import { useNavigate } from 'react-router-dom';
import type { SpanRow, TraceRow } from '../../../../api';
import { fetchSpansForTrace, isToolCallSpan, serviceOf } from '../../tracesApi';
import { serviceColor } from '../traceColors';
import { tokenBreakdownForSpan } from '../../tokenBreakdown';
import TraceSummaryInlineView, {
  type OpGroup,
  type TraceSummaryModel,
} from './TraceSummaryInlineView';

interface TraceSummaryInlineProps {
  trace: TraceRow;
}

const TraceSummaryInline = ({ trace }: TraceSummaryInlineProps) => {
  const navigate = useNavigate();
  const { data: spans, isLoading } = useQuery({
    queryKey: ['trace-inline-spans', trace.traceId],
    queryFn: () => fetchSpansForTrace(trace.traceId),
  });

  const model = useMemo<TraceSummaryModel | null>(() => {
    if (!spans || spans.length === 0) {
      return null;
    }
    const spanById = new Map(spans.map((span) => [span.spanId, span]));
    const startMs = Math.min(
      ...spans.map((span) => Date.parse(span.startTimestamp)),
    );
    const endMs = Math.max(
      ...spans.map((span) => Date.parse(span.endTimestamp)),
    );
    const totalMs = Math.max(1, endMs - startMs);
    const childrenByParent = new Map<string, SpanRow[]>();
    spans.forEach((span) => {
      if (span.parentSpanId) {
        const children = childrenByParent.get(span.parentSpanId) ?? [];
        children.push(span);
        childrenByParent.set(span.parentSpanId, children);
      }
    });
    const depthOf = (span: SpanRow): number => {
      let depth = 0;
      let currentSpan: SpanRow | undefined = span;
      while (
        currentSpan?.parentSpanId &&
        spanById.has(currentSpan.parentSpanId)
      ) {
        depth += 1;
        currentSpan = spanById.get(currentSpan.parentSpanId);
      }
      return depth;
    };
    // Self-time = own duration minus the duration of direct children (avoids
    // double-counting nested spans, so the breakdown sums to ~wall-clock).
    const selfTimeMsBySpanId = new Map<string, number>();
    spans.forEach((span) => {
      const children = childrenByParent.get(span.spanId) ?? [];
      const childDurationMs = children.reduce(
        (a, k) => a + k.durationNanos / 1e6,
        0,
      );
      selfTimeMsBySpanId.set(
        span.spanId,
        Math.max(0, span.durationNanos / 1e6 - childDurationMs),
      );
    });

    // Group self-time by operation name.
    const groups = new Map<string, OpGroup>();
    spans.forEach((span) => {
      const group = groups.get(span.name) ?? {
        name: span.name,
        selfTimeMs: 0,
        count: 0,
        errorCount: 0,
      };
      group.selfTimeMs += selfTimeMsBySpanId.get(span.spanId) ?? 0;
      group.count += 1;
      if (span.statusCode === 'error') {
        group.errorCount += 1;
      }
      groups.set(span.name, group);
    });
    const operations = [...groups.values()].sort(
      (a, b) => b.selfTimeMs - a.selfTimeMs,
    );
    const TOP_OPERATION_COUNT = 6;
    const shownOperations = operations.slice(0, TOP_OPERATION_COUNT);
    const remainingOperations = operations.slice(TOP_OPERATION_COUNT);
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

    // Token composition (4-way) summed across spans from real attributes.
    const tokenTotals = spans.reduce(
      (accumulator, span) => {
        const breakdown = tokenBreakdownForSpan(span);
        accumulator.input += breakdown.input;
        accumulator.output += breakdown.output;
        accumulator.cacheRead += breakdown.cacheRead;
        accumulator.cacheCreate += breakdown.cacheCreate;
        accumulator.total += breakdown.total;
        return accumulator;
      },
      { input: 0, output: 0, cacheRead: 0, cacheCreate: 0, total: 0 },
    );
    const calls = spans.filter(
      (span) => tokenBreakdownForSpan(span).total > 0,
    ).length;

    const maxDepth =
      spans.reduce((m, span) => Math.max(m, depthOf(span)), 0) + 1;

    // Tool/MCP invocations — surfaced as the KPI strip's "Tool calls" tile.
    const toolCalls = spans.filter((span) => isToolCallSpan(span.name)).length;

    return {
      totalMs,
      shownOperations,
      opCount: groups.size,
      tokenTotals,
      calls,
      maxDepth,
      toolCalls,
    };
  }, [spans]);

  const serviceHue = serviceColor(serviceOf(trace.rootSpanName));
  const callLabel = model
    ? model.calls
      ? `${model.calls} model ${model.calls === 1 ? 'call' : 'calls'}`
      : 'no model calls'
    : '';

  return (
    <TraceSummaryInlineView
      trace={trace}
      model={model}
      isLoading={isLoading}
      serviceHue={serviceHue}
      callLabel={callLabel}
      onOpenTrace={() => navigate(`/traces/${trace.traceId}`)}
    />
  );
};

export default TraceSummaryInline;
