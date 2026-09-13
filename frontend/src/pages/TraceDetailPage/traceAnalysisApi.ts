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
// Fetchers for the trace-analysis sub-resource (`/api/traces/{traceId}/analysis`).
// Page-local because only AnalyzeTraceDialog on this page calls it. `fetchTraceAnalysis`
// bypasses the shared `getJson` helper — the same pattern `SettingsPage/settingsApi.ts
// #purgeTelemetry` uses — so a failure's plain-text backend body (a 503 "Ollama unreachable"
// message, or a 404) can be read and shown to the user rather than collapsed into a generic
// status-text error.

import { parseServerSentEvent, readServerSentEvents } from '../../lib/serverSentEvents';

export interface TraceAnalysisResult {
  traceId: string;
  model: string;
  analysis: string;
  generationDurationMs: number;
  generatedAt: string;
  // MAX(end_timestamp) across the trace's spans as of generation -- the trace
  // activity this analysis actually covers.
  analyzedThroughTimestamp: string;
  // True when the trace has picked up spans since analyzedThroughTimestamp (a
  // still-running trace, or a late background/subagent completion) -- the
  // analysis may no longer reflect everything that happened.
  outdated: boolean;
  // The request this analysis judged, verbatim -- the "before" the Better
  // wording advice is shown against. Null for a trace whose wording nobody
  // authored (a slash command, a task notification, a subagent run), and on
  // every analysis stored before that column existed, so the card renders the
  // suggestion alone rather than assuming a comparison is always available.
  userPrompt: string | null;
  // LEGACY. True when ollama.max-prompt-chars forced the call timeline to be
  // elided when this analysis was generated -- the review was written from a
  // partial timeline, not the whole trace. Only ever true on a row stored
  // before the windowed-review rework: a new analysis always partitions an
  // oversized timeline into passes (see reviewPassCount) rather than dropping
  // any of it, so this is always false and omittedLineCount always 0 for a
  // fresh run. False for every analysis stored before this field existed,
  // which reads the same to a user as "not truncated".
  timelineTruncated: boolean;
  // LEGACY, paired with timelineTruncated above. How many condensed timeline
  // lines were dropped from the middle to fit the budget, or 0 when
  // timelineTruncated is false (which now includes every fresh run). Lines,
  // not raw calls -- a collapsed repeat is already one line for several calls.
  omittedLineCount: number;
  // How many windows/passes the review took, always >= 1. A trace whose
  // timeline fit in one model call reviews it in a single pass; an oversized
  // one is split into consecutive, non-overlapping windows, each reviewed as
  // its own drafting pass and then merged in code -- so no call is ever
  // dropped the way a truncated legacy row's was. 1 for every analysis stored
  // before this field existed.
  reviewPassCount: number;
  // Total calls in the trace's timeline -- the N that reviewPassCount
  // partitioned. 0 for every analysis stored before this field existed.
  timelineCallCount: number;
  // A plain-text "what happened" recap -- request, work, outcome -- composed
  // entirely by the backend from the same facts the review's overview is built
  // from, and never sent to Ollama: it carries no judgment and cites no call
  // numbers a reader would need to verify. Render as plain text, same as
  // `analysis`. Null for an analysis stored before this field existed.
  summary: string | null;
}

/**
 * Loads any previously saved analysis for this trace. Resolves to `null` on a
 * 404 rather than throwing — "not analyzed yet" is a normal state here, not an
 * error, and this call never triggers an Ollama run.
 */
export const fetchTraceAnalysis = async (
  traceId: string,
): Promise<TraceAnalysisResult | null> => {
  const path = `/api/traces/${traceId}/analysis`;
  const response = await fetch(path);
  if (response.status === 404) {
    return null;
  }
  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    throw new Error(detail || `${path} → ${response.status} ${response.statusText}`);
  }
  return response.json() as Promise<TraceAnalysisResult>;
};

/**
 * One step of a run, as named by the backend's TraceAnalysisPhase enum
 * (READING_TRACE, BUILDING_PROMPT, DRAFTING, MERGING, APPLYING, SAVING).
 *
 * `key` is `phase` for a phase that only ever happens once, and a
 * per-occurrence identity (e.g. `"DRAFTING#2"`) when the windowed review
 * splits an oversized timeline into multiple passes and a phase like DRAFTING
 * recurs once per window. `stepNumber`/`stepCount` name that repeat ("pass 2
 * of 3"); both are 1 for a phase that only ever happens once. Key on `key`,
 * never on `phase`, wherever a phase is used as a React list key or matched
 * against `activeKey` — two DRAFTING occurrences sharing a key would collide
 * on the checklist and flip an already-done row back to active.
 */
export interface TraceAnalysisPhase {
  phase: string;
  key: string;
  // Rendered as-is. The backend owns this wording so adding or renaming a phase
  // there needs no change here — don't reintroduce a client-side label table.
  label: string;
  stepNumber: number;
  stepCount: number;
}

/** The `phase` SSE event's payload — which occurrence just became active. */
export interface TraceAnalysisPhaseEvent {
  phase: string;
  key: string;
  label?: string;
  stepNumber: number;
  stepCount: number;
}

/** The `delta` SSE event's payload — one piece of output from one pass. */
export interface TraceAnalysisDeltaEvent {
  phase: string;
  key: string;
  text: string;
  // Monotonically non-decreasing across the WHOLE run (every pass), not just
  // within one phase's occurrence.
  characters: number;
}

export interface TraceAnalysisStreamHandlers {
  // The full ordered phase list, sent immediately and optimistically — before
  // the backend has finished preparing the prompt, so it may not reflect the
  // final pass count. Lets the dialog show a checklist up front rather than
  // growing it a line at a time.
  onStarted: (phases: TraceAnalysisPhase[]) => void;
  // Same payload shape as onStarted, sent once the real window/pass count is
  // known (after the backend finishes preparing the prompt) — replaces the
  // optimistic list from `started` with the accurate one.
  onPlan: (phases: TraceAnalysisPhase[]) => void;
  onPhase: (phaseEvent: TraceAnalysisPhaseEvent) => void;
  // `delta.text` is '' when the backend is on the structured-output path (the
  // answer is JSON being assembled, not the review) — the caller falls back to
  // reporting `delta.characters` rather than rendering a live draft. No flag
  // needed: the absence of text is the signal. `delta.key` is what a caller
  // must key an accumulating draft buffer on: a fresh key (a new pass, or the
  // "apply this" step after drafting) means fresh text, not a continuation.
  onDelta: (delta: TraceAnalysisDeltaEvent) => void;
}

/**
 * Runs a fresh analysis (first run or "Regenerate") and reports the run as it
 * happens, resolving with the same `TraceAnalysisResult` shape `fetchTraceAnalysis`
 * loads for a stored row. This is the only way the dialog runs/regenerates an
 * analysis — a local inference run is seconds to minutes, and streaming is the
 * difference between that time being a spinner or the review appearing as the
 * model writes it.
 *
 * **Failures arrive as an event, not as a status code.** The response commits at
 * 200 before the work starts, so an unknown trace id and an unreachable Ollama
 * both come back as a `failed` event; this turns them into a thrown Error
 * carrying the backend's own user-facing message, so callers get one try/catch
 * shape regardless of which failure mode fired.
 */
export const streamTraceAnalysis = async (
  traceId: string,
  handlers: TraceAnalysisStreamHandlers,
): Promise<TraceAnalysisResult> => {
  const path = `/api/traces/${traceId}/analysis/stream`;
  const response = await fetch(path, { method: 'POST' });
  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    throw new Error(detail || `${path} → ${response.status} ${response.statusText}`);
  }

  let result: TraceAnalysisResult | null = null;
  let failure: string | null = null;

  const handleFrame = (frame: string) => {
    const { event, data } = parseServerSentEvent(frame);
    if (!data) {
      return;
    }
    if (event === 'started') {
      handlers.onStarted((JSON.parse(data) as { phases: TraceAnalysisPhase[] }).phases);
    } else if (event === 'plan') {
      handlers.onPlan((JSON.parse(data) as { phases: TraceAnalysisPhase[] }).phases);
    } else if (event === 'phase') {
      handlers.onPhase(JSON.parse(data) as TraceAnalysisPhaseEvent);
    } else if (event === 'delta') {
      handlers.onDelta(JSON.parse(data) as TraceAnalysisDeltaEvent);
    } else if (event === 'done') {
      result = JSON.parse(data) as TraceAnalysisResult;
    } else if (event === 'failed') {
      failure = (JSON.parse(data) as { message: string }).message;
    }
  };

  await readServerSentEvents(response, handleFrame);

  if (failure) {
    throw new Error(failure);
  }
  if (!result) {
    // The connection dropped before either terminal event. Reported rather than
    // resolved with nothing, since the run may well have finished and been saved
    // — reopening the dialog is what tells the user which.
    throw new Error(
      'The analysis stream ended before a result arrived — reopen this dialog to see whether it finished.',
    );
  }
  return result;
};
