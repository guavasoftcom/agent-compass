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
import { parseServerSentEvent, readServerSentEvents } from './serverSentEvents';

describe('parseServerSentEvent', () => {
  it('reads the event name and single-line data payload', () => {
    expect(parseServerSentEvent('event:phase\ndata:{"phase":"DRAFTING"}')).toEqual({
      event: 'phase',
      data: '{"phase":"DRAFTING"}',
    });
  });

  it('defaults to a "message" event when no event: line is present', () => {
    expect(parseServerSentEvent('data:hello')).toEqual({ event: 'message', data: 'hello' });
  });

  it('rejoins multiple data: lines with newlines, per the SSE spec', () => {
    expect(parseServerSentEvent('event:delta\ndata:line one\ndata:line two')).toEqual({
      event: 'delta',
      data: 'line one\nline two',
    });
  });
});

describe('readServerSentEvents', () => {
  const streamedResponse = (chunks: string[]) => {
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(encoder.encode(chunk));
        }
        controller.close();
      },
    });
    return new Response(body);
  };

  it('splits a body into frames on the blank line separating them', async () => {
    const onFrame = vi.fn();

    await readServerSentEvents(
      streamedResponse(['event:started\ndata:{}\n\nevent:done\ndata:{}\n\n']),
      onFrame,
    );

    expect(onFrame.mock.calls).toEqual([['event:started\ndata:{}'], ['event:done\ndata:{}']]);
  });

  it('reassembles a frame split across chunk boundaries, including mid-JSON', async () => {
    const wholeBody = 'event:delta\ndata:{"text":"half here, half there"}\n\n';
    const chunks: string[] = [];
    for (let index = 0; index < wholeBody.length; index += 7) {
      chunks.push(wholeBody.slice(index, index + 7));
    }
    const onFrame = vi.fn();

    await readServerSentEvents(streamedResponse(chunks), onFrame);

    expect(onFrame).toHaveBeenCalledWith('event:delta\ndata:{"text":"half here, half there"}');
  });

  it('flushes a trailing frame with no terminating blank line once the stream ends', async () => {
    const onFrame = vi.fn();

    await readServerSentEvents(streamedResponse(['event:phase\ndata:{}']), onFrame);

    expect(onFrame).toHaveBeenCalledWith('event:phase\ndata:{}');
  });

  it('falls back to reading the whole body as text when response.body is missing', async () => {
    const onFrame = vi.fn();
    const bodylessResponse = {
      body: null,
      text: () => Promise.resolve('event:done\ndata:{}\n\n'),
    } as unknown as Response;

    await readServerSentEvents(bodylessResponse, onFrame);

    expect(onFrame).toHaveBeenCalledWith('event:done\ndata:{}');
  });
});
