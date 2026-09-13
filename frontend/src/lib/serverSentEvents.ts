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
// A generic Server-Sent Events frame reader/parser. Nothing here is specific to any one endpoint —
// it just turns a streamed `Response` into `{ event, data }` frames — so it lives here rather than
// inside the one page-local fetcher (`pages/TraceDetailPage/traceAnalysisApi.ts`) that is currently
// this app's only SSE consumer.

export const FRAME_SEPARATOR = '\n\n';

/**
 * Splits an SSE body into frames on the blank line between them and hands each to `onFrame`.
 *
 * Falls back to reading the whole body as text when `response.body` is missing. That is not a
 * browser case — it is jsdom, which implements `fetch` bodies but not always the stream — and
 * reading a finished body in one go produces exactly the same frames, just all at once.
 */
export const readServerSentEvents = async (
  response: Response,
  onFrame: (frame: string) => void,
): Promise<void> => {
  const forEachFrameIn = (text: string, isFinalChunk: boolean) => {
    let remainder = text;
    let separatorIndex = remainder.indexOf(FRAME_SEPARATOR);
    while (separatorIndex !== -1) {
      onFrame(remainder.slice(0, separatorIndex));
      remainder = remainder.slice(separatorIndex + FRAME_SEPARATOR.length);
      separatorIndex = remainder.indexOf(FRAME_SEPARATOR);
    }
    if (isFinalChunk && remainder.trim()) {
      onFrame(remainder);
    }
    return remainder;
  };

  if (!response.body) {
    forEachFrameIn(await response.text(), true);
    return;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      forEachFrameIn(buffer + decoder.decode(), true);
      return;
    }
    buffer = forEachFrameIn(buffer + decoder.decode(value, { stream: true }), false);
  }
};

/**
 * Reads one frame's `event:` name and `data:` payload. Data lines are rejoined with newlines per
 * the SSE spec — a server may write JSON on a single line, but a parser that assumes that breaks
 * silently rather than loudly if it ever stops being true.
 */
export const parseServerSentEvent = (frame: string): { event: string; data: string } => {
  let event = 'message';
  const dataLines: string[] = [];
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).replace(/^ /, ''));
    }
  }
  return { event, data: dataLines.join('\n') };
};
