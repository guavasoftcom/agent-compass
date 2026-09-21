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
import AnalyzeTraceDialogView from './AnalyzeTraceDialogView';
import type { TraceAnalysisResult } from '../../traceAnalysisApi';
import type { SpanRow, TraceCostBreakdown } from '../../../../api';

const storedAnalysis: TraceAnalysisResult = {
  traceId: 'trace-0102',
  model: 'llama3.1',
  analysis: 'This trace shows a Read call that only exists because the prompt\ndid not name the file directly.',
  generationDurationMs: 4200,
  generatedAt: '2026-08-30T10:05:00.000Z',
  analyzedThroughTimestamp: '2026-08-30T10:04:55.000Z',
  outdated: false,
  // Null unless a test is exercising the wording comparison: most stored rows
  // have no judged request (a slash command, a notification, a subagent run),
  // and neither does any analysis written before the column existed.
  userPrompt: null,
  timelineTruncated: false,
  omittedLineCount: 0,
  reviewPassCount: 1,
  timelineCallCount: 24,
  summary: null,
};

// As the backend's `started` event sends them — ids plus the labels it owns, so
// these fixtures mirror TraceAnalysisPhase rather than restating the wording.
// A single-pass run: every phase happens once, so `key` mirrors `phase` and
// `stepCount` is 1 throughout (no " — pass N of M" suffix renders).
const streamedPhases = [
  { phase: 'READING_TRACE', key: 'READING_TRACE', label: 'Reading trace spans & logs', stepNumber: 1, stepCount: 1 },
  { phase: 'BUILDING_PROMPT', key: 'BUILDING_PROMPT', label: 'Reviewing tool calls, errors & prompt quality', stepNumber: 1, stepCount: 1 },
  { phase: 'DRAFTING', key: 'DRAFTING', label: 'Drafting review', stepNumber: 1, stepCount: 1 },
  { phase: 'SAVING', key: 'SAVING', label: 'Saving the review', stepNumber: 1, stepCount: 1 },
];

// A windowed review whose oversized timeline took two DRAFTING passes — the
// direct fixture for the React-key regression test below: two entries share
// `phase: 'DRAFTING'` but carry distinct `key`s, the way the backend actually
// disambiguates a repeated phase.
const streamedPhasesWithRepeatedDrafting = [
  { phase: 'READING_TRACE', key: 'READING_TRACE', label: 'Reading trace spans & logs', stepNumber: 1, stepCount: 1 },
  { phase: 'BUILDING_PROMPT', key: 'BUILDING_PROMPT', label: 'Reviewing tool calls, errors & prompt quality', stepNumber: 1, stepCount: 1 },
  { phase: 'DRAFTING', key: 'DRAFTING#1', label: 'Drafting review', stepNumber: 1, stepCount: 2 },
  { phase: 'DRAFTING', key: 'DRAFTING#2', label: 'Drafting review', stepNumber: 2, stepCount: 2 },
  { phase: 'SAVING', key: 'SAVING', label: 'Saving the review', stepNumber: 1, stepCount: 1 },
];

describe('AnalyzeTraceDialogView', () => {
  it('shows a spinner while the stored analysis query is loading', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis
        analysis={null}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText('Checking for a saved analysis…')).toBeInTheDocument();
  });

  it('shows the explanation and a "Run analysis" button when nothing is stored', async () => {
    const user = userEvent.setup();
    const onRunAnalysis = vi.fn();
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={null}
        onRunAnalysis={onRunAnalysis}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText(/Send this trace's overview, prompt, tool calls/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Run analysis' }));
    expect(onRunAnalysis).toHaveBeenCalledTimes(1);
  });

  it('shows no phase checklist while regenerating but before the stream has reported anything', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={null}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating
        regenerateError={null}
      />,
    );

    // No phases named yet: the checklist is the backend's to populate, and
    // inventing rows here is exactly the timer-driven fiction this replaced.
    expect(screen.getByText('Starting the analysis…')).toBeInTheDocument();
  });

  it('shows the error message and a "Try again" button when regeneration failed', async () => {
    const user = userEvent.setup();
    const onRegenerate = vi.fn();
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={null}
        onRunAnalysis={vi.fn()}
        onRegenerate={onRegenerate}
        isRegenerating={false}
        regenerateError={new Error('Could not reach Ollama at http://localhost:11434 — is it running?')}
      />,
    );

    expect(
      screen.getByText('Could not reach Ollama at http://localhost:11434 — is it running?'),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRegenerate).toHaveBeenCalledTimes(1);
  });

  it('renders the analysis text as plain text with a caption and action buttons', async () => {
    const user = userEvent.setup();
    const onRegenerate = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={onClose}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={storedAnalysis}
        onRunAnalysis={vi.fn()}
        onRegenerate={onRegenerate}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText(/This trace shows a Read call/)).toBeInTheDocument();
    expect(screen.getByText('llama3.1', { exact: false })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Regenerate' }));
    expect(onRegenerate).toHaveBeenCalledTimes(1);

    const closeButtons = screen.getAllByRole('button', { name: 'Close' });
    await user.click(closeButtons[0]);
    expect(onClose).toHaveBeenCalled();
  });

  it('renders backticked spans as inline code, not as block code', () => {
    // react-markdown 9 dropped the `inline` prop a custom `code` component used
    // to branch on, which silently turned every inline span into a full-width
    // <pre> block and broke each sentence in half.
    const { baseElement } = renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{
          ...storedAnalysis,
          analysis: 'Fix: Never run `find` in Bash — use Glob instead.',
        }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText('find').tagName).toBe('CODE');
    expect(baseElement.querySelector('pre')).toBeNull();
  });

  it('turns a cited call number into a button that shows the call', async () => {
    // The number a review cites is the analysis timeline's, which is not the
    // waterfall's index badge — so without this the reader has no way from
    // "Call 20 was an outlier" to the row it means.
    const onNavigateToCall = vi.fn();
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{
          ...storedAnalysis,
          analysis: 'Call 20 was an outlier, and calls 15 and 16 re-read one file.',
        }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
        knownCallNumbers={new Set([15, 16, 20])}
        onNavigateToCall={onNavigateToCall}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Show call 20 in the waterfall' }));

    expect(onNavigateToCall).toHaveBeenCalledWith(20);
    expect(screen.getByRole('button', { name: 'Show call 16 in the waterfall' })).toBeInTheDocument();
  });

  it('renders a cited call the trace does not have as a marked, non-clickable span', () => {
    // A number the model invented: no call this trace has to land a link on,
    // but distinct enough from ordinary text that a reader can tell it was
    // meant as a citation.
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{ ...storedAnalysis, analysis: 'Call 99 was an outlier.' }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
        knownCallNumbers={new Set([15, 16, 20])}
        onNavigateToCall={vi.fn()}
      />,
    );

    expect(screen.queryByRole('button', { name: /Show call 99/ })).not.toBeInTheDocument();
    const citation = screen.getByText('99');
    expect(citation.tagName).toBe('SPAN');
    expect(citation).toHaveAttribute('title', 'This trace has no call 99');
    expect(screen.getByText(/Call/)).toBeInTheDocument();
    expect(screen.getByText(/was an outlier\./)).toBeInTheDocument();
  });

  it('promotes a bold-only paragraph to a section heading', () => {
    // The model writes its section titles as "**What went wrong**" rather than
    // as a markdown heading; a finding that merely starts bold stays a bullet.
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{
          ...storedAnalysis,
          analysis: '**What went wrong**\n\n- **Bash Misuse** — a shell command.',
        }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByRole('heading', { name: 'What went wrong' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /Bash Misuse/ })).not.toBeInTheDocument();
  });

  it('shows an out-of-date warning when the trace has newer span activity than the analysis', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{ ...storedAnalysis, outdated: true }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText(/had new activity since this analysis was generated/)).toBeInTheDocument();
  });

  it('shows no out-of-date warning when the analysis is current', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={storedAnalysis}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.queryByText(/had new activity since this analysis was generated/)).not.toBeInTheDocument();
  });

  it('shows a truncated-timeline notice with the omitted count when the timeline had to be elided', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{ ...storedAnalysis, timelineTruncated: true, omittedLineCount: 42 }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText(/42 calls were left out of the middle/)).toBeInTheDocument();
    // Only ever true on an old stored row now — the wording says so, pointing
    // the reader at regenerating rather than leaving it ambiguous whether a
    // fresh review could still drop calls.
    expect(
      screen.getByText(/predates full-timeline coverage; regenerate for one that sees every call/),
    ).toBeInTheDocument();
  });

  it('shows a multi-pass notice when the timeline was too big for one review pass', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{ ...storedAnalysis, reviewPassCount: 3, timelineCallCount: 140 }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(
      screen.getByText(
        /This trace's 140 calls were too many to review in one pass, so they were reviewed in 3 consecutive passes and the findings combined\. Every call was seen\./,
      ),
    ).toBeInTheDocument();
  });

  it('shows no multi-pass notice when the whole timeline fit in one pass', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{ ...storedAnalysis, reviewPassCount: 1 }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.queryByText(/too many to review in one pass/)).not.toBeInTheDocument();
  });

  it('shows the code-composed summary ahead of the model findings', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{
          ...storedAnalysis,
          summary: 'Request: "fix the flaky token test"\n'
            + 'Work: 4 tool calls · 2 model calls · 18.2s\n'
            + 'Tools: Read ×3 · Edit\n'
            + 'Files: TokenService.java (Read ×2, Edit) · AGENTS.md (Read)\n'
            + 'Outcome: no errors; ended: "Fixed it."',
        }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    // The backend's own line prefix is "Request"; the card relabels it "Prompt", since next to
    // "Work"/"Outcome" that is the noun for what is actually shown — the user's own prompt text.
    expect(screen.getByText('Prompt')).toBeInTheDocument();
    expect(screen.getByText('"fix the flaky token test"')).toBeInTheDocument();
    expect(screen.getByText('Work')).toBeInTheDocument();
    expect(screen.getByText('Outcome')).toBeInTheDocument();
    // A multi-clause line splits into its own bullets, on the middle dot and never on a comma —
    // so a file keeps the tool list in its own parentheses.
    expect(screen.getByText('Read ×3')).toBeInTheDocument();
    expect(screen.getByText('TokenService.java (Read ×2, Edit)')).toBeInTheDocument();
  });

  // The summary card's Tools/Models/Files/Cost sections are computed client-side from spans
  // the page already loaded (summarizeTraceWork), never sent to or parsed from the backend.
  // They sit behind a collapsed-by-default toggle; Work's content is replaced with a small
  // stat line computed the same way — see summarizeTraceWork.ts and its own test file.
  let nextSpanId = 1;
  const summaryWorkSpan = (overrides: Partial<SpanRow> & { name: string }): SpanRow => {
    const id = nextSpanId;
    nextSpanId += 1;
    return {
      id,
      spanId: `span-${id}`,
      parentSpanId: null,
      traceId: 'trace-0102',
      kind: null,
      startTimestamp: '2026-08-30T10:00:00.000Z',
      endTimestamp: '2026-08-30T10:00:01.000Z',
      durationNanos: 1_000_000_000,
      statusCode: 'ok',
      statusMessage: null,
      scopeName: 'claude_code',
      attributes: null,
      events: null,
      resourceAttributes: null,
      ...overrides,
    };
  };

  const summaryWorkSpans: SpanRow[] = [
    summaryWorkSpan({
      name: 'claude_code.tool',
      attributes: { tool_name: 'Read', file_path: 'TracesPageView.tsx' },
    }),
    summaryWorkSpan({
      name: 'claude_code.tool',
      attributes: { tool_name: 'Edit', file_path: 'TracesPageView.tsx' },
    }),
    summaryWorkSpan({
      name: 'claude_code.tool',
      attributes: { tool_name: 'Read', file_path: 'AGENTS.md' },
    }),
    summaryWorkSpan({
      name: 'claude_code.llm_request',
      attributes: { model: 'claude-sonnet-4', input_tokens: 100, output_tokens: 20 },
      costUsd: 0.02,
    }),
  ];

  const summaryAnalysisWithComputedWork: TraceAnalysisResult = {
    ...storedAnalysis,
    summary: 'Request: "fix the flaky token test"\nWork: some free-text work clauses\nOutcome: no errors',
  };

  it('shows the computed Work stat line and keeps the details collapsed by default', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={summaryAnalysisWithComputedWork}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
        spans={summaryWorkSpans}
        logsBySpanId={new Map()}
        traceCostUsd={0.05}
      />,
    );

    // Work's content is replaced by duration alone — the tool/model call counts it used to
    // repeat now live only in the Tools/Models sections' own labels, and the backend's own
    // "some free-text work clauses" text must not survive either.
    expect(screen.getByText('1.00 s')).toBeInTheDocument();
    expect(screen.queryByText('3 tool calls')).not.toBeInTheDocument();
    expect(screen.queryByText('some free-text work clauses')).not.toBeInTheDocument();

    // Outcome is replaced by a short computed status line with an ok/warning icon —
    // "no errors" renders as "No errors".
    expect(screen.getByText('No errors')).toBeInTheDocument();
    expect(screen.queryByText('no errors')).not.toBeInTheDocument();

    // Collapsed by default: the toggle is offered but the sections underneath are not rendered.
    expect(screen.getByRole('button', { name: 'Show Tools, Models, Files, Cost' })).toBeInTheDocument();
    expect(screen.queryByText('TracesPageView.tsx')).not.toBeInTheDocument();
    expect(screen.queryByText('Sonnet 4')).not.toBeInTheDocument();
  });

  it('expands the Tools/Models/Files/Cost sections on toggle, with a count badge only on a repeated name', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={summaryAnalysisWithComputedWork}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
        spans={summaryWorkSpans}
        logsBySpanId={new Map()}
        traceCostUsd={0.05}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Show Tools, Models, Files, Cost' }));

    expect(screen.getByRole('button', { name: 'Hide Tools, Models, Files, Cost' })).toBeInTheDocument();
    // Section labels now carry their own counts, rather than a bare toggle with no indication
    // of what's behind it.
    expect(screen.getByText('Tools (3)')).toBeInTheDocument();
    expect(screen.getByText('Models (1)')).toBeInTheDocument();
    expect(screen.getByText('Files (2)')).toBeInTheDocument();
    // "Read" appears twice across the trace and gets a ×2 badge (in the Tools section only —
    // the Files section's own tool chips never carry a count); "Edit" appears once and gets none.
    expect(screen.getByText('×2')).toBeInTheDocument();
    expect(screen.queryByText('×1')).not.toBeInTheDocument();
    // Models — a short name, via the shared shortModelName formatter.
    expect(screen.getByText('Sonnet 4')).toBeInTheDocument();
    // Files — the path next to the tool chips that touched it. "Read" touched both files, so
    // its chip renders once per file row (plus once in the Tools section = 3 total); "Edit"
    // touched only one (plus once in Tools = 2 total).
    expect(screen.getByText('TracesPageView.tsx')).toBeInTheDocument();
    expect(screen.getByText('AGENTS.md')).toBeInTheDocument();
    // Each file row leads with a colored language badge read off its extension.
    expect(screen.getByText('TSX')).toBeInTheDocument();
    expect(screen.getByText('MD')).toBeInTheDocument();
    expect(screen.getAllByText('Read')).toHaveLength(3);
    expect(screen.getAllByText('Edit')).toHaveLength(2);
    // Cost — the backend-authoritative total, never a client-side sum. Its own label
    // carries the dollar figure, so the body doesn't repeat a redundant "Total:" line.
    expect(screen.getByText('Cost ($0.050)')).toBeInTheDocument();
    expect(screen.queryByText(/^Total:/)).not.toBeInTheDocument();
    expect(screen.getByText(/measured across 1 model call/)).toBeInTheDocument();
  });

  const subagentCostBreakdown: TraceCostBreakdown = {
    subagentCosts: [
      {
        subagentLabel: 'Explore',
        dispatchCallNumber: 12,
        costUsd: 0.42,
        modelCallCount: 6,
        toolCallCount: 3,
      },
    ],
    mainLoopCostUsd: 1.23,
    mainLoopModelCallCount: 8,
    auxiliaryCostUsd: 0,
    auxiliaryModelCallCount: 0,
    measuredCostUsd: 1.65,
  };

  it('shows a per-subagent cost row, with a call-number link when the trace has that call', async () => {
    const user = userEvent.setup();
    const onNavigateToCall = vi.fn();
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={summaryAnalysisWithComputedWork}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
        spans={summaryWorkSpans}
        logsBySpanId={new Map()}
        traceCostUsd={0.05}
        costBreakdown={subagentCostBreakdown}
        knownCallNumbers={new Set([12])}
        onNavigateToCall={onNavigateToCall}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Show Tools, Models, Files, Cost' }));

    expect(screen.getByText('Explore')).toBeInTheDocument();
    expect(screen.getByText(/\$0\.42/)).toBeInTheDocument();
    expect(screen.getByText(/6 model calls/)).toBeInTheDocument();
    expect(screen.getByText(/3 tool calls/)).toBeInTheDocument();

    const callButton = screen.getByRole('button', { name: 'Show call 12 in the waterfall' });
    await user.click(callButton);
    expect(onNavigateToCall).toHaveBeenCalledWith(12);
  });

  it('renders a subagent dispatch call number as plain text when the trace has no known calls', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={summaryAnalysisWithComputedWork}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
        spans={summaryWorkSpans}
        logsBySpanId={new Map()}
        traceCostUsd={0.05}
        costBreakdown={subagentCostBreakdown}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Show Tools, Models, Files, Cost' }));

    expect(screen.getByText('call 12')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Show call 12 in the waterfall' }),
    ).not.toBeInTheDocument();
  });

  it('omits the subagent cost breakdown entirely when the trace dispatched no subagents', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={summaryAnalysisWithComputedWork}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
        spans={summaryWorkSpans}
        logsBySpanId={new Map()}
        traceCostUsd={0.05}
        costBreakdown={{ ...subagentCostBreakdown, subagentCosts: [] }}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Show Tools, Models, Files, Cost' }));

    expect(screen.queryByText(/attributed across the main loop/)).not.toBeInTheDocument();
  });

  it('truncates a long directory from the front but keeps the filename intact, with the full path in a title tooltip', async () => {
    const user = userEvent.setup();
    const longPath = 'frontend/src/pages/TraceDetailPage/components/AnalyzeTraceDialog/AnalyzeTraceDialogView.tsx';
    const spans = [
      summaryWorkSpan({
        name: 'claude_code.tool',
        attributes: { tool_name: 'Read', file_path: longPath },
      }),
    ];

    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={summaryAnalysisWithComputedWork}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
        spans={spans}
        logsBySpanId={new Map()}
        traceCostUsd={0.05}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Show Tools, Models, Files, Cost' }));

    const pathElement = screen.getByTitle(longPath);
    // The full path is never shown directly — only ever behind the title tooltip.
    expect(screen.queryByText(longPath)).not.toBeInTheDocument();
    // Only the directory can lose characters, and only off its front — the ellipsis leads the
    // directory text, and the filename that follows it survives whole.
    expect(pathElement.textContent).toContain('…');
    expect(pathElement.textContent?.endsWith('AnalyzeTraceDialogView.tsx')).toBe(true);
    expect(screen.getByText('AnalyzeTraceDialogView.tsx')).toBeInTheDocument();
  });

  it('strips the directory prefix every file shares, so each row shows only what differs', async () => {
    const user = userEvent.setup();
    const spans = [
      summaryWorkSpan({
        name: 'claude_code.tool',
        attributes: {
          tool_name: 'Edit',
          file_path: '/Users/guadalupegarcia/Projects/agent-compass/backend/src/main/java/com/aurora/TraceAnalysisService.java',
        },
      }),
      summaryWorkSpan({
        name: 'claude_code.tool',
        attributes: {
          tool_name: 'Edit',
          file_path: '/Users/guadalupegarcia/Projects/agent-compass/frontend/src/api/traceAnalysisApi.ts',
        },
      }),
    ];

    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={summaryAnalysisWithComputedWork}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
        spans={spans}
        logsBySpanId={new Map()}
        traceCostUsd={0.05}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Show Tools, Models, Files, Cost' }));

    // The shared "/Users/guadalupegarcia/Projects/agent-compass" prefix is gone from both
    // rows' visible text — it's still in the full-path title tooltip, but nowhere a reader reads
    // by default.
    expect(screen.queryByText(/\/Users\/guadalupegarcia/)).not.toBeInTheDocument();
    expect(
      screen.getByTitle('/Users/guadalupegarcia/Projects/agent-compass/backend/src/main/java/com/aurora/TraceAnalysisService.java'),
    ).toBeInTheDocument();
    expect(
      screen.getByTitle('/Users/guadalupegarcia/Projects/agent-compass/frontend/src/api/traceAnalysisApi.ts'),
    ).toBeInTheDocument();
    // Each filename still renders whole, next to its own (shorter, still-differing) directory.
    expect(screen.getByText('TraceAnalysisService.java')).toBeInTheDocument();
    expect(screen.getByText('traceAnalysisApi.ts')).toBeInTheDocument();
  });

  it('renders no details toggle when the trace has nothing for the supplementary sections to show', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={summaryAnalysisWithComputedWork}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
        spans={[]}
        logsBySpanId={new Map()}
        traceCostUsd={null}
      />,
    );

    expect(screen.queryByRole('button', { name: /Tools, Models, Files, Cost/ })).not.toBeInTheDocument();
    expect(screen.getByText('0.0 ms')).toBeInTheDocument();
  });

  /**
   * Stored analyses are re-read, never migrated, so a summary written before the separator changed
   * has no middle dot in it. It must still render — as one line per fact rather than as bullets —
   * instead of being shredded on the commas inside its own quoted prompt.
   */
  it('renders a summary stored before the clause separator existed without splitting its prose', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{
          ...storedAnalysis,
          summary: 'Request: "fix the cache total, then run the tests"\n'
            + 'Work: 4 tool calls and 2 model calls over 18.2s, costing $0.0123\n'
            + 'Outcome: no errors; ended: "Fixed it."',
        }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText('"fix the cache total, then run the tests"')).toBeInTheDocument();
    expect(screen.getByText('4 tool calls and 2 model calls over 18.2s, costing $0.0123')).toBeInTheDocument();
  });

  it('renders no summary card for an analysis stored before the field existed', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={storedAnalysis}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.queryByText('Request')).not.toBeInTheDocument();
  });

  it('shows no truncated-timeline notice when the whole timeline fit', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={storedAnalysis}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.queryByText(/left out of the middle/)).not.toBeInTheDocument();
  });

  it('splits a fixed-format "Apply this" section into per-item cards with their own copy buttons', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });

    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{
          ...storedAnalysis,
          analysis:
            '**What went wrong**\n\n- Ran `Read` right before an equivalent `Glob`. Fix: glob first.\n\n'
            + '**Apply this**\n\n'
            + 'CLAUDE.md rule: `Confirm the test runner is on PATH before invoking it directly.`\n'
            + 'Tool swap: `Use Bash with poetry run pytest instead of pytest.`\n'
            + 'Rewritten request: `Refactor the auth middleware to use the new token store.`',
        }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByRole('heading', { name: 'Apply this' })).toBeInTheDocument();
    // This fixture deliberately uses BOTH legacy prefixes — `CLAUDE.md rule:` from before
    // the rule line grew a target, and `Rewritten request:` from while the wording advice
    // was framed as a request to paste and re-run. Every analysis stored under either
    // still reads back that way, so both must keep parsing, under the current labels.
    expect(screen.getByText('Instruction rule')).toBeInTheDocument();
    expect(screen.getByText('Tool swap')).toBeInTheDocument();
    expect(screen.getByText('Better wording')).toBeInTheDocument();
    expect(
      screen.getByText('Confirm the test runner is on PATH before invoking it directly.'),
    ).toBeInTheDocument();
    // Raw backtick markup and the "Apply this" heading line are consumed by the
    // split, not left behind as literal text in the markdown box above it.
    expect(screen.queryByText(/CLAUDE\.md rule:/)).not.toBeInTheDocument();

    const copyButtons = screen.getAllByRole('button', { name: 'Copy' });
    // One per apply item; the footer's whole-analysis button is labeled "Copy all" instead.
    expect(copyButtons).toHaveLength(3);
    await user.click(copyButtons[0]);
    expect(writeText).toHaveBeenCalledWith('Confirm the test runner is on PATH before invoking it directly.');
  });

  it('drops an "Apply this" line the model answered with None', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{
          ...storedAnalysis,
          analysis:
            '**What went wrong**\n\nNothing worth changing.\n\n'
            + '**Apply this**\n\n'
            + 'CLAUDE.md rule: None\n'
            + 'Tool swap: None\n'
            + 'Better wording: `Name the file: say "fix the cache-read total in MetricPointRepository".`',
        }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.queryByText('Instruction rule')).not.toBeInTheDocument();
    expect(screen.queryByText('Tool swap')).not.toBeInTheDocument();
    expect(screen.getByText('Better wording')).toBeInTheDocument();
  });

  // The wording advice is about a request that has already run, so it is only
  // advice next to the words it replaces. These three pin that: the comparison
  // when the original is stored, the suggestion alone when it is not, and the
  // expander that keeps a long pasted request from burying the suggestion.
  const analysisWithWordingAdvice = (userPrompt: string | null): TraceAnalysisResult => ({
    ...storedAnalysis,
    userPrompt,
    analysis:
      '**What went wrong**\n\nThe agent spent six calls finding the file.\n\n'
      + '**Apply this**\n\n'
      + 'Instruction rule: None\n'
      + 'Tool swap: None\n'
      + 'Better wording: `Name the file: say "fix the cache-read total in '
      + 'MetricPointRepository" rather than "fix the token thing".`',
  });

  it('shows the wording advice against the request that was actually sent', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={analysisWithWordingAdvice("fix the token thing, it's wrong somewhere in the backend")}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText('You wrote')).toBeInTheDocument();
    expect(
      screen.getByText("fix the token thing, it's wrong somewhere in the backend"),
    ).toBeInTheDocument();
    expect(screen.getByText('Say instead, next time')).toBeInTheDocument();
    expect(
      screen.getByText(/Name the file: say "fix the cache-read total in MetricPointRepository"/),
    ).toBeInTheDocument();
  });

  it('shows the wording advice alone when no original request was stored with the analysis', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={analysisWithWordingAdvice(null)}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    // An analysis stored before the original request was kept must still render
    // its advice rather than an empty half of a comparison.
    expect(screen.getByText('Better wording')).toBeInTheDocument();
    expect(
      screen.getByText(/Name the file: say "fix the cache-read total in MetricPointRepository"/),
    ).toBeInTheDocument();
    expect(screen.queryByText('You wrote')).not.toBeInTheDocument();
  });

  it('offers to expand an original request too long to show under the clamp', async () => {
    const user = userEvent.setup();
    const longRequest = `${'Refactor the auth middleware. '.repeat(20)}Then run the tests.`;
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={analysisWithWordingAdvice(longRequest)}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Show full request' }));
    expect(screen.getByRole('button', { name: 'Show less' })).toBeInTheDocument();
  });

  it('offers no expander on a request the clamp already shows whole', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={analysisWithWordingAdvice('fix the token thing')}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText('fix the token thing')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show full request' })).not.toBeInTheDocument();
  });

  it('renders the instruction rule target as a chip and copies only the rule text', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });

    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{
          ...storedAnalysis,
          analysis:
            '**What went wrong**\n\nThe skill re-read the same file twice.\n\n'
            + '**Apply this**\n\n'
            + 'Instruction rule: `skill:ship — Read the diff once, at the length you need.`\n'
            + 'Tool swap: None\n'
            + 'Better wording: None',
        }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText('Instruction rule')).toBeInTheDocument();
    expect(screen.getByText('skill:ship')).toBeInTheDocument();
    // The target is a label, not part of what gets pasted into the file.
    const copyButtons = screen.getAllByRole('button', { name: 'Copy' });
    await user.click(copyButtons[0]);
    expect(writeText).toHaveBeenCalledWith('Read the diff once, at the length you need.');
  });

  it('reads the target off the trailing half when the model writes rule-then-target', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });

    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{
          ...storedAnalysis,
          // The order the prompt asks for is `<target> — <rule>`, but real stored
          // analyses come back reversed, with the rule backticked and the target
          // trailing outside it. Read positionally that put the whole rule in the
          // chip and "CLAUDE.md" in the copyable body.
          analysis:
            '**What went wrong**\n\nThe agent re-read the same file.\n\n'
            + '**Apply this**\n\n'
            + 'Instruction rule: `Read a file once at the length you need.` — CLAUDE.md\n'
            + 'Tool swap: None\n'
            + 'Better wording: None',
        }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText('CLAUDE.md')).toBeInTheDocument();
    expect(screen.getByText('Read a file once at the length you need.')).toBeInTheDocument();
    const copyButtons = screen.getAllByRole('button', { name: 'Copy' });
    await user.click(copyButtons[0]);
    expect(writeText).toHaveBeenCalledWith('Read a file once at the length you need.');
  });

  it('keeps an em dash inside an instruction rule that names no target', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{
          ...storedAnalysis,
          analysis:
            '**What went wrong**\n\nThe agent re-read the same file.\n\n'
            + '**Apply this**\n\n'
            + 'Instruction rule: `Read a file once — at the length you need.`\n'
            + 'Tool swap: None\n'
            + 'Better wording: None',
        }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText('Read a file once — at the length you need.')).toBeInTheDocument();
    expect(screen.queryByText('Read a file once')).not.toBeInTheDocument();
  });

  it('keeps an em dash inside a tool swap rather than reading it as a target', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{
          ...storedAnalysis,
          analysis:
            '**What went wrong**\n\nUsed find.\n\n'
            + '**Apply this**\n\n'
            + 'Instruction rule: None\n'
            + 'Tool swap: `Use Glob instead of find — it is indexed.`\n'
            + 'Better wording: None',
        }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByText('Use Glob instead of find — it is indexed.')).toBeInTheDocument();
    expect(screen.queryByText('Use Glob instead of find')).not.toBeInTheDocument();
  });

  it('falls back to plain markdown when the analysis does not follow the fixed "Apply this" format', () => {
    // A stored analysis generated before the backend prompt asked for fixed
    // line prefixes (or a model response that ignored the instruction) should
    // stay readable rather than losing its "Apply this" section entirely.
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={{
          ...storedAnalysis,
          analysis:
            '**What went wrong**\n\nNothing worth changing.\n\n'
            + '**Apply this**\n\n'
            + 'Rule for CLAUDE.md: None\nDo instead: None\nPrompt to use next time: None',
        }}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating={false}
        regenerateError={null}
      />,
    );

    expect(screen.getByRole('heading', { name: 'Apply this' })).toBeInTheDocument();
    expect(screen.getByText(/Rule for CLAUDE\.md:/)).toBeInTheDocument();
  });

  it('disables the Regenerate button while a regeneration is already in flight', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={storedAnalysis}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating
        regenerateError={null}
      />,
    );

    // isRegenerating takes priority, so the result view isn't rendered at
    // all while a run is in flight — the progress state above covers the
    // "disabled while pending" contract for the Regenerate button.
    expect(screen.getByText('Starting the analysis…')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Regenerate/ })).not.toBeInTheDocument();
  });

  // NOTE ON THIS TEST: it previously asserted a bare "step 3 of 4" string and
  // that the phases behind/ahead of the active one were not rendered at all.
  // Neither ever matched this view's actual behavior — AnalysisRunProgressView
  // has always rendered the full checklist (every phase, in order, styled
  // done/active/pending), never a single-step-only display, and there has
  // never been a plain "step N of M" string anywhere in this component. That
  // assertion failed before this task's changes too (confirmed by running the
  // suite against the pre-existing code). Rewritten to assert the real
  // contract: the whole checklist renders, and the active phase is the one
  // whose label is present alongside the live character count.
  it('shows the whole checklist with the active phase carrying the live character count', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={null}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating
        regenerateError={null}
        progress={{
          phases: streamedPhases,
          activeKey: 'DRAFTING',
          draftKey: 'DRAFTING',
          draftText: 'a',
          draftCharacters: 1,
        }}
      />,
    );

    expect(screen.getByText('Reading trace spans & logs')).toBeInTheDocument();
    expect(screen.getByText('Drafting review')).toBeInTheDocument();
    expect(screen.getByText('Saving the review')).toBeInTheDocument();
    expect(screen.getByText('1 characters')).toBeInTheDocument();
  });

  // Direct regression test for the React-key collision this task fixes: a
  // repeated phase (DRAFTING recurring once per review pass) used to share a
  // React `key` with its earlier occurrence, which could flip an
  // already-rendered "done" row back to "active"/"pending" on reconciliation.
  // Keying on `phase.key` (a distinct identity per occurrence) instead of
  // `phase.phase` is what keeps the earlier occurrence stable.
  it('keeps an earlier occurrence of a repeated phase rendered as its own row when a later occurrence becomes active', () => {
    const { rerender } = renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={null}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating
        regenerateError={null}
        progress={{
          phases: streamedPhasesWithRepeatedDrafting,
          activeKey: 'DRAFTING#1',
          draftKey: 'DRAFTING#1',
          draftText: 'First pass draft.',
          draftCharacters: 17,
        }}
      />,
    );

    expect(screen.getByText('Drafting review — pass 1 of 2')).toBeInTheDocument();

    rerender(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={null}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating
        regenerateError={null}
        progress={{
          phases: streamedPhasesWithRepeatedDrafting,
          activeKey: 'DRAFTING#2',
          draftKey: 'DRAFTING#2',
          draftText: 'Second pass draft.',
          draftCharacters: 18,
        }}
      />,
    );

    // Both occurrences still render as their own row — the first did not
    // disappear or get overwritten by the second becoming active.
    expect(screen.getByText('Drafting review — pass 1 of 2')).toBeInTheDocument();
    expect(screen.getByText('Drafting review — pass 2 of 2')).toBeInTheDocument();
  });

  it('holds the panel with a skeleton until the model starts writing, then drops it', () => {
    // The skeleton's whole job is to be the same box the draft lands in, so the
    // two must never be on screen together and the panel must never be empty.
    const { baseElement } = renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={null}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating
        regenerateError={null}
        progress={{
          phases: streamedPhases,
          activeKey: 'DRAFTING',
          draftKey: 'DRAFTING',
          draftText: 'The agent read the same file twice.',
          draftCharacters: 35,
        }}
      />,
    );

    expect(baseElement.querySelector('.MuiSkeleton-root')).toBeNull();
    expect(screen.getByText(/The agent read the same file twice/)).toBeInTheDocument();
  });

  it('renders the model output as markdown while it is still being written', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={null}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating
        regenerateError={null}
        progress={{
          phases: streamedPhases,
          activeKey: 'DRAFTING',
          draftKey: 'DRAFTING',
          draftText: '**What went wrong**\n\n- The agent read `auth.js` three tim',
          draftCharacters: 54,
        }}
      />,
    );

    // A half-written draft renders as markdown like any other: the bold-only
    // paragraph is promoted, the backticked span is inline code, and the
    // sentence cut mid-word is simply the text so far.
    expect(screen.getByRole('heading', { name: 'What went wrong' })).toBeInTheDocument();
    expect(screen.getByText('auth.js').tagName).toBe('CODE');
    expect(screen.getByText(/three tim$/)).toBeInTheDocument();
  });

  /**
   * The structured-output path assembles a JSON document rather than the review,
   * so there is no readable draft to show and the count is the only honest
   * progress signal. Pinned because the view decides between the two by looking
   * at whether text arrived, not by reading a flag.
   */
  it('falls back to a character count when the stream reports progress but no text', () => {
    renderWithProviders(
      <AnalyzeTraceDialogView
        open
        onClose={vi.fn()}
        traceId="trace-0102"
        isLoadingStoredAnalysis={false}
        analysis={null}
        onRunAnalysis={vi.fn()}
        onRegenerate={vi.fn()}
        isRegenerating
        regenerateError={null}
        progress={{
          phases: streamedPhases,
          activeKey: 'DRAFTING',
          draftKey: 'DRAFTING',
          draftText: '',
          draftCharacters: 1204,
        }}
      />,
    );

    expect(screen.getByText('1,204 characters')).toBeInTheDocument();
  });
});
