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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { streamTraceAnalysis } from './traceAnalysisApi';

const TRACE_ID = 'trace-0102';

const storedAnalysis = {
  traceId: TRACE_ID,
  model: 'llama3.1',
  analysis: 'This trace reads one file twice.',
  generationDurationMs: 9000,
  generatedAt: '2026-08-30T10:05:00.000Z',
  analyzedThroughTimestamp: '2026-08-30T10:04:55.000Z',
  outdated: false,
  userPrompt: 'fix the token thing',
  timelineTruncated: false,
  omittedLineCount: 0,
  reviewPassCount: 1,
  timelineCallCount: 42,
};

const frame = (event: string, payload: unknown) =>
  `event:${event}\ndata:${JSON.stringify(payload)}\n\n`;

/**
 * Serves `chunks` as a streamed response body, one `read()` per chunk. Chunk
 * boundaries are the whole point of the tests below: a real network splits an
 * SSE body wherever it likes, including mid-frame and mid-JSON.
 */
const respondWithStream = (chunks: string[], status = 200) => {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
      controller.close();
    },
  });
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(body, { status })),
  );
};

const noOpHandlers = () => ({
  onStarted: vi.fn(),
  onPlan: vi.fn(),
  onPhase: vi.fn(),
  onDelta: vi.fn(),
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('streamTraceAnalysis', () => {
  it('reports every event in order and resolves with the stored analysis', async () => {
    respondWithStream([
      frame('started', {
        phases: [{ phase: 'DRAFTING', key: 'DRAFTING', label: 'Drafting review', stepNumber: 1, stepCount: 1 }],
      }),
      frame('phase', { phase: 'DRAFTING', key: 'DRAFTING', label: 'Drafting review', stepNumber: 1, stepCount: 1 }),
      frame('delta', { phase: 'DRAFTING', key: 'DRAFTING', text: 'This trace ', characters: 11 }),
      frame('delta', { phase: 'DRAFTING', key: 'DRAFTING', text: 'reads one file twice.', characters: 32 }),
      frame('done', storedAnalysis),
    ]);
    const handlers = noOpHandlers();

    const result = await streamTraceAnalysis(TRACE_ID, handlers);

    expect(handlers.onStarted).toHaveBeenCalledWith([
      { phase: 'DRAFTING', key: 'DRAFTING', label: 'Drafting review', stepNumber: 1, stepCount: 1 },
    ]);
    expect(handlers.onPhase).toHaveBeenCalledWith({
      phase: 'DRAFTING',
      key: 'DRAFTING',
      label: 'Drafting review',
      stepNumber: 1,
      stepCount: 1,
    });
    expect(handlers.onDelta.mock.calls).toEqual([
      [{ phase: 'DRAFTING', key: 'DRAFTING', text: 'This trace ', characters: 11 }],
      [{ phase: 'DRAFTING', key: 'DRAFTING', text: 'reads one file twice.', characters: 32 }],
    ]);
    expect(result).toEqual(storedAnalysis);
  });

  // Sent once the real window/pass count is known (after the backend finishes
  // preparing the prompt), replacing the optimistic list `started` sent.
  it('reports a plan event with the same payload shape as started', async () => {
    const planPhases = [
      { phase: 'DRAFTING', key: 'DRAFTING#1', label: 'Drafting review', stepNumber: 1, stepCount: 2 },
      { phase: 'DRAFTING', key: 'DRAFTING#2', label: 'Drafting review', stepNumber: 2, stepCount: 2 },
    ];
    respondWithStream([
      frame('started', { phases: [{ phase: 'DRAFTING', key: 'DRAFTING', label: 'Drafting review', stepNumber: 1, stepCount: 1 }] }),
      frame('plan', { phases: planPhases }),
      frame('done', storedAnalysis),
    ]);
    const handlers = noOpHandlers();

    await streamTraceAnalysis(TRACE_ID, handlers);

    expect(handlers.onPlan).toHaveBeenCalledWith(planPhases);
  });

  /**
   * The failure mode this parser exists to avoid. A body arriving in
   * network-sized pieces splits frames — and the JSON inside them — at arbitrary
   * offsets, so anything that parses per-chunk rather than per-frame throws on a
   * run that would otherwise have succeeded.
   */
  it('reassembles frames split across chunk boundaries, including mid-JSON', async () => {
    const wholeBody =
      frame('phase', { phase: 'DRAFTING', key: 'DRAFTING', label: 'Drafting review', stepNumber: 1, stepCount: 1 }) +
      frame('delta', { phase: 'DRAFTING', key: 'DRAFTING', text: 'half here, half there', characters: 21 }) +
      frame('done', storedAnalysis);
    const chunks = [];
    for (let i = 0; i < wholeBody.length; i += 7) {
      chunks.push(wholeBody.slice(i, i + 7));
    }
    respondWithStream(chunks);
    const handlers = noOpHandlers();

    const result = await streamTraceAnalysis(TRACE_ID, handlers);

    expect(handlers.onDelta).toHaveBeenCalledWith({
      phase: 'DRAFTING',
      key: 'DRAFTING',
      text: 'half here, half there',
      characters: 21,
    });
    expect(result).toEqual(storedAnalysis);
  });

  /**
   * By the time the run starts the response is committed at 200, so an
   * unreachable Ollama has no status code left to travel as. It arrives as a
   * `failed` event, and callers keep the plain try/catch shape the non-streaming
   * fetcher gave them only if that is rethrown with the backend's own wording.
   */
  it('throws the backend message carried by a failed event', async () => {
    respondWithStream([
      frame('phase', { phase: 'DRAFTING' }),
      frame('failed', { message: 'Could not reach Ollama at http://localhost:11434 — is it running?' }),
    ]);

    await expect(streamTraceAnalysis(TRACE_ID, noOpHandlers())).rejects.toThrow(
      'Could not reach Ollama at http://localhost:11434 — is it running?',
    );
  });

  it('throws rather than resolving empty when the stream ends with no terminal event', async () => {
    respondWithStream([frame('phase', { phase: 'DRAFTING' })]);

    await expect(streamTraceAnalysis(TRACE_ID, noOpHandlers())).rejects.toThrow(
      /ended before a result arrived/,
    );
  });

  it('surfaces a non-200 response body as the error, since no stream ever started', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('Trace analysis is disabled', { status: 503 })),
    );

    await expect(streamTraceAnalysis(TRACE_ID, noOpHandlers())).rejects.toThrow(
      'Trace analysis is disabled',
    );
  });
});
