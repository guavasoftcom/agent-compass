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
import { useEffect, useRef, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { fetchTraceCostBreakdown } from '../../../../api';
import type { LogRow, SpanRow } from '../../../../api';
import {
  fetchTraceAnalysis,
  streamTraceAnalysis,
} from '../../traceAnalysisApi';
import AnalyzeTraceDialogView from './AnalyzeTraceDialogView';
import type { AnalysisRunProgress } from './AnalyzeTraceDialogView';

interface Props {
  open: boolean;
  onClose: () => void;
  traceId: string;
  // Passed straight through to the view, which turns a citation like "call 20"
  // into a button that shows the span it means — see its own props for why a
  // number outside knownCallNumbers stays plain text. Resolved by the page,
  // which owns the spans and the waterfall selection.
  knownCallNumbers?: Set<number>;
  onNavigateToCall?: (callNumber: number) => void;
  // Also passed straight through: the trace's own spans and per-span log
  // buckets, already fetched by the page. Feed the summary card's
  // client-computed Work/Tools/Models/Files/Cost sections (summarizeTraceWork
  // and costOfSelectedSpan) — see TraceSummaryCard in AnalyzeTraceDialogView.tsx.
  // Optional so the view's own test fixtures (and a caller with no waterfall to
  // draw from) can omit them; the view then falls back to the backend-only
  // Work text with no supplementary sections.
  spans?: SpanRow[];
  logsBySpanId?: Map<string, LogRow[]>;
  // Backend-authoritative trace cost from the page's own ['trace-summary',
  // traceId] query (TraceRow.totalCostUsd) — passed straight through to the
  // summary card's Cost section, never re-derived here. See spanCost.ts and
  // TraceDetailPage/CLAUDE.md's Cost section for why a client-side sum must
  // never stand in for it.
  traceCostUsd?: number | null;
}

// Nothing has been reported yet — the state a run starts from, and the state
// between runs. `phases` empty is what the view reads as "the stream hasn't
// said what the steps are yet", so it shows a plain spinner rather than an
// empty checklist.
const NO_PROGRESS: AnalysisRunProgress = {
  phases: [],
  activeKey: null,
  draftKey: null,
  draftText: '',
  draftCharacters: 0,
};

// READING_TRACE and BUILDING_PROMPT are both real phases, but both finish in
// single-digit milliseconds — the backend reports them back to back, and a
// state update that fast never gets painted before the next one replaces it,
// so a reader never sees either one. This holds each phase on screen for at
// least this long before advancing to the next queued one, so the reveal is
// paced for a human even though the underlying events are not. It changes
// nothing about which phases exist or their order — DRAFTING and SAVING,
// which already take real time, are unaffected.
const MINIMUM_PHASE_DISPLAY_MS = 550;

// Container: on open, loads any stored analysis (`enabled: open` — nothing
// fetches until the dialog is first opened) and exposes a mutation for
// "Run analysis" / "Regenerate" that writes its result straight into the
// query cache on success, so the view re-renders with the fresh analysis
// immediately without a refetch.
//
// The mutation runs the STREAMING fetcher, so the run reports itself while it
// is in flight: `progress` accumulates the backend's phase events and the
// model's output as it is written, and the view renders the draft in the same
// markdown container the finished analysis lands in. That is the whole reason
// this is a stateful container rather than a bare `mutate()` — react-query
// models a call's result, and what matters here happens before there is one.
const AnalyzeTraceDialog = ({
  open,
  onClose,
  traceId,
  knownCallNumbers,
  onNavigateToCall,
  spans,
  logsBySpanId,
  traceCostUsd,
}: Props) => {
  const queryClient = useQueryClient();
  const [progress, setProgress] = useState<AnalysisRunProgress>(NO_PROGRESS);
  // Whether the reader has ever expanded TraceSummaryCard's collapsed-by-default
  // "Show Tools, Models, Files, Cost" section this time the dialog is open — see
  // costBreakdownQuery below. Reset naturally on every open since this container
  // is only mounted while the dialog is (TraceDetailPageView's
  // analyzeTraceDialogOpen), the same reason `progress` needs no reset-on-open
  // logic of its own.
  const [supplementaryOpen, setSupplementaryOpen] = useState(false);

  // Keys not yet shown, and the timer draining them one at a time — see
  // MINIMUM_PHASE_DISPLAY_MS. Keyed on the phase event's `key`, not its
  // `phase`, so a repeated phase (DRAFTING recurring once per review window)
  // paces through each occurrence in turn rather than colliding on the same
  // identity. Refs, not state: this is pacing for a state update, not
  // something the view reads directly.
  const phaseKeyQueueRef = useRef<string[]>([]);
  const isDrainingPhaseKeyQueueRef = useRef(false);
  const phaseTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearPhaseQueue = () => {
    if (phaseTimeoutRef.current !== null) {
      clearTimeout(phaseTimeoutRef.current);
      phaseTimeoutRef.current = null;
    }
    phaseKeyQueueRef.current = [];
    isDrainingPhaseKeyQueueRef.current = false;
  };

  // Shows the next queued key immediately, then waits out the minimum display
  // time before draining the one after it. Called once per key shown, so
  // DRAFTING and SAVING — which arrive with real gaps between them — are
  // unaffected: by the time either arrives the queue is already empty and
  // this fires again immediately, same as the very first phase does.
  const drainPhaseKeyQueue = () => {
    const nextKey = phaseKeyQueueRef.current.shift();
    if (nextKey === undefined) {
      isDrainingPhaseKeyQueueRef.current = false;
      return;
    }
    isDrainingPhaseKeyQueueRef.current = true;
    setProgress((current) => ({ ...current, activeKey: nextKey }));
    phaseTimeoutRef.current = setTimeout(drainPhaseKeyQueue, MINIMUM_PHASE_DISPLAY_MS);
  };

  const enqueuePhaseKey = (key: string) => {
    phaseKeyQueueRef.current.push(key);
    if (!isDrainingPhaseKeyQueueRef.current) {
      drainPhaseKeyQueue();
    }
  };

  // A pending timer must not fire after the dialog/page it updates is gone.
  useEffect(() => clearPhaseQueue, []);

  const storedAnalysisQuery = useQuery({
    queryKey: ['trace-analysis', traceId],
    queryFn: () => fetchTraceAnalysis(traceId),
    enabled: open,
  });

  // Per-subagent cost breakdown for the summary card's Cost section. That
  // section starts collapsed (TraceSummaryCard's own "Show Tools, Models,
  // Files, Cost" toggle) and its subagent rows only render when there is at
  // least one, so this stays disabled until the reader actually expands it
  // (`supplementaryOpen`, flipped true by `onSupplementaryExpand` below) rather
  // than firing on every dialog open regardless of whether that section is ever
  // opened. Once true it stays true for the rest of this mount — collapsing the
  // section again doesn't need a refetch, and react-query's own cache means
  // re-expanding it costs nothing. Not window-scoped, like the analysis query,
  // and cheap to keep out of the mutation entirely since it never changes as a
  // result of running/regenerating a review.
  const costBreakdownQuery = useQuery({
    queryKey: ['trace-cost-breakdown', traceId],
    queryFn: () => fetchTraceCostBreakdown(traceId),
    enabled: open && supplementaryOpen,
  });

  const regenerateMutation = useMutation({
    mutationFn: () =>
      streamTraceAnalysis(traceId, {
        onStarted: (phases) => setProgress((current) => ({ ...current, phases })),
        // Sent once the real window/pass count is known — same reducer as
        // onStarted, it just replaces the optimistic list with the accurate
        // one.
        onPlan: (phases) => setProgress((current) => ({ ...current, phases })),
        onPhase: (phaseEvent) => enqueuePhaseKey(phaseEvent.key),
        // `delta.key` is what stops a second review window's draft from
        // appending onto the first window's, and — with no special-casing —
        // also what stops the "apply this" step's own delta text from
        // bleeding onto the findings draft that preceded it: any key change
        // starts a fresh buffer.
        onDelta: (delta) =>
          setProgress((current) => ({
            ...current,
            draftKey: delta.key,
            draftText: delta.key === current.draftKey ? current.draftText + delta.text : delta.text,
            draftCharacters: delta.characters,
          })),
      }),
    // Reset here rather than in the view: a second "Regenerate" must not show
    // the previous run's draft while the new one is still gathering.
    onMutate: () => {
      clearPhaseQueue();
      setProgress(NO_PROGRESS);
    },
    onSuccess: (result) => {
      queryClient.setQueryData(['trace-analysis', traceId], result);
    },
  });

  return (
    <AnalyzeTraceDialogView
      open={open}
      onClose={onClose}
      traceId={traceId}
      isLoadingStoredAnalysis={storedAnalysisQuery.isLoading}
      // The mutation's onSuccess writes straight into this same query's cache
      // entry, so a freshly regenerated result shows up here too — no need to
      // thread mutation.data through separately.
      analysis={storedAnalysisQuery.data ?? null}
      onRunAnalysis={() => regenerateMutation.mutate()}
      onRegenerate={() => regenerateMutation.mutate()}
      isRegenerating={regenerateMutation.isPending}
      regenerateError={regenerateMutation.error as Error | null}
      progress={progress}
      knownCallNumbers={knownCallNumbers}
      onNavigateToCall={onNavigateToCall}
      spans={spans}
      logsBySpanId={logsBySpanId}
      traceCostUsd={traceCostUsd}
      costBreakdown={costBreakdownQuery.data ?? null}
      onSupplementaryExpand={() => setSupplementaryOpen(true)}
    />
  );
};

export default AnalyzeTraceDialog;
