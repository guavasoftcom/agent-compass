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
import { describe, expect, it } from 'vitest';
import {
  callNumberFromHref,
  remarkCallCitations,
  splitCallCitations,
  splitRepeatOfCallCitation,
  unknownCallNumberFromHref,
} from './callCitations';

const callsIn = (text: string) =>
  splitCallCitations(text)
    .filter((segment) => segment.kind === 'call')
    .map((segment) => segment.callNumber);

const rejoin = (text: string) =>
  splitCallCitations(text)
    .map((segment) => segment.text)
    .join('');

describe('splitCallCitations', () => {
  it('links the number in a single citation and leaves the sentence intact', () => {
    const segments = splitCallCitations('Call 20 was an outlier, taking 35.6s.');

    expect(segments).toEqual([
      { kind: 'text', text: 'Call ' },
      { kind: 'call', text: '20', callNumber: 20 },
      { kind: 'text', text: ' was an outlier, taking 35.6s.' },
    ]);
  });

  it('links every number of a chained citation', () => {
    expect(callsIn('as seen in calls 15 and 16 where the same file was read')).toEqual([15, 16]);
    expect(callsIn('calls 3, 4 and 7 all ran find')).toEqual([3, 4, 7]);
    expect(callsIn('calls 31 through 95 are one opaque block')).toEqual([31, 95]);
  });

  // The prompt template asks the model to "cite call numbers", so this is the
  // phrasing real reviews come back in — trace bc222c551f5acf3c77fc44f8bc53c0f8
  // wrote every citation in its "What went wrong" section this way.
  it('links a citation written as "call number(s) N"', () => {
    expect(callsIn('call numbers 27, 29, 31, 35, 37 all edited OllamaClient.java')).toEqual([
      27, 29, 31, 35, 37,
    ]);
    expect(callsIn('read twice (call numbers 22, 43 and 23, 49 respectively)')).toEqual([
      22, 43, 23, 49,
    ]);
    expect(callsIn('the outlier was call number 3')).toEqual([3]);
    expect(callsIn('see call #12 and #13')).toEqual([12, 13]);
  });

  // Trace 9b65e40faab46fe018b1065e7dd8d63a wrote every citation in its "What
  // went wrong" section as "call(s) N, N, N" — a third phrasing distinct from
  // both the plain "calls N" and the "call numbers N" cases above.
  it('links a citation written as "call(s) N"', () => {
    expect(callsIn('reading `Foo.java` at call(s) 15, 145, 153')).toEqual([15, 145, 153]);
    expect(callsIn('used `find` at call(s) 6 and 10')).toEqual([6, 10]);
  });

  // The failure this guards against is the loud one: a review is full of
  // durations, token counts and dollar figures, and linking those to a call
  // number they have nothing to do with would send the reader to a random row.
  it('ignores numbers that are not part of a call citation', () => {
    expect(callsIn('the trace cost $0.42 across 3 model calls in 35.6s')).toEqual([]);
    expect(callsIn('it read 12 files and wrote 2')).toEqual([]);
  });

  it('never changes the text it splits', () => {
    const review = 'Call 20 was an outlier; calls 15 and 16 re-read Foo.java (3 times, 2.1s).';

    expect(rejoin(review)).toBe(review);
  });
});

describe('splitRepeatOfCallCitation', () => {
  it('links only the digits inside a "(repeat of call N)" phrase', () => {
    const segments = splitRepeatOfCallCitation('llm_request (repeat of call 11)');

    expect(segments).toEqual([
      { kind: 'text', text: 'llm_request (repeat of call ' },
      { kind: 'call', text: '11', callNumber: 11 },
      { kind: 'text', text: ')' },
    ]);
  });

  // Real command/path literals quoted as inline code (e.g. `grep -n "call 20"
  // file.txt`) must not be mistaken for this phrase — only the exact backend
  // shape should ever match.
  it('ignores text that is not the exact backend phrase', () => {
    expect(splitRepeatOfCallCitation('grep -n "call 20" file.txt')).toEqual([
      { kind: 'text', text: 'grep -n "call 20" file.txt' },
    ]);
    expect(splitRepeatOfCallCitation('repeat of call 20')).toEqual([
      { kind: 'text', text: 'repeat of call 20' },
    ]);
  });
});

describe('callNumberFromHref', () => {
  it('reads back only its own hrefs', () => {
    expect(callNumberFromHref('#call-20')).toBe(20);
    expect(callNumberFromHref('#section')).toBeNull();
    expect(callNumberFromHref('https://example.test/call-20')).toBeNull();
    expect(callNumberFromHref(undefined)).toBeNull();
    // The unknown-call scheme is a distinct href shape, not a suffix of this
    // one — it must not be read back as a known call.
    expect(callNumberFromHref('#unknown-call-20')).toBeNull();
  });
});

describe('unknownCallNumberFromHref', () => {
  it('reads back only its own hrefs', () => {
    expect(unknownCallNumberFromHref('#unknown-call-99')).toBe(99);
    expect(unknownCallNumberFromHref('#section')).toBeNull();
    expect(unknownCallNumberFromHref('https://example.test/unknown-call-99')).toBeNull();
    expect(unknownCallNumberFromHref(undefined)).toBeNull();
    // The known-call scheme is a distinct href shape — must not be read back
    // as an unknown call.
    expect(unknownCallNumberFromHref('#call-99')).toBeNull();
  });
});

describe('remarkCallCitations', () => {
  const paragraph = (value: string) => ({
    type: 'root',
    children: [{ type: 'paragraph', children: [{ type: 'text', value }] }],
  });

  it('rewrites a cited call into a link the view can render as a button', () => {
    const tree = paragraph('Call 20 was an outlier.');

    remarkCallCitations(() => true)()(tree);

    expect(tree.children[0].children).toEqual([
      { type: 'text', value: 'Call ' },
      { type: 'link', url: '#call-20', children: [{ type: 'text', value: '20' }] },
      { type: 'text', value: ' was an outlier.' },
    ]);
  });

  // The model can cite a number the trace does not have — one past the end of
  // the timeline. That is now unambiguously invented (no call is ever elided
  // any more), so it is rewritten to a distinctly-marked, non-clickable
  // citation rather than folded into indistinguishable plain text.
  it('rewrites a call number the trace does not have into a marked, non-linking citation', () => {
    const tree = paragraph('Call 99 was an outlier.');

    remarkCallCitations((callNumber) => callNumber <= 20)()(tree);

    expect(tree.children[0].children).toEqual([
      { type: 'text', value: 'Call ' },
      { type: 'link', url: '#unknown-call-99', children: [{ type: 'text', value: '99' }] },
      { type: 'text', value: ' was an outlier.' },
    ]);
  });

  // A backticked command or path is something the reader copies; rewriting
  // inside one would corrupt it.
  it('does not touch inline code', () => {
    const tree = {
      type: 'root',
      children: [
        {
          type: 'paragraph',
          children: [{ type: 'inlineCode', value: 'grep -n "call 20" file.txt' }],
        },
      ],
    };

    remarkCallCitations(() => true)()(tree);

    expect(tree.children[0].children).toEqual([
      { type: 'inlineCode', value: 'grep -n "call 20" file.txt' },
    ]);
  });

  // Trace 9b65e40faab46fe018b1065e7dd8d63a quoted the timeline's own
  // "(repeat of call N)" wording as inline code, and the plain "does not touch
  // inline code" rule above left it dead. This is the one inline-code shape
  // that gets a link.
  it('links the number in a "(repeat of call N)" phrase quoted as inline code', () => {
    const tree = {
      type: 'root',
      children: [
        {
          type: 'paragraph',
          children: [{ type: 'inlineCode', value: 'llm_request (repeat of call 11)' }],
        },
      ],
    };

    remarkCallCitations(() => true)()(tree);

    expect(tree.children[0].children).toEqual([
      { type: 'inlineCode', value: 'llm_request (repeat of call ' },
      { type: 'link', url: '#call-11', children: [{ type: 'inlineCode', value: '11' }] },
      { type: 'inlineCode', value: ')' },
    ]);
  });

  it('rewrites a "(repeat of call N)" phrase into a marked, non-linking citation when the trace does not have that call', () => {
    const tree = {
      type: 'root',
      children: [
        {
          type: 'paragraph',
          children: [{ type: 'inlineCode', value: 'llm_request (repeat of call 99)' }],
        },
      ],
    };

    remarkCallCitations((callNumber) => callNumber <= 20)()(tree);

    expect(tree.children[0].children).toEqual([
      { type: 'inlineCode', value: 'llm_request (repeat of call ' },
      { type: 'link', url: '#unknown-call-99', children: [{ type: 'inlineCode', value: '99' }] },
      { type: 'inlineCode', value: ')' },
    ]);
  });

  it('rewrites citations nested inside a bold list item', () => {
    const tree = {
      type: 'root',
      children: [
        {
          type: 'list',
          children: [
            {
              type: 'listItem',
              children: [
                {
                  type: 'paragraph',
                  children: [
                    { type: 'strong', children: [{ type: 'text', value: 'Wrong instrument' }] },
                    { type: 'text', value: ' — calls 15 and 16 read the same file.' },
                  ],
                },
              ],
            },
          ],
        },
      ],
    };

    remarkCallCitations(() => true)()(tree);

    // The fixture is a plain object literal, so its inferred member type has no
    // `url` — the plugin adds link nodes that were not in the literal.
    const paragraphChildren: { type: string; url?: string }[] =
      tree.children[0].children[0].children[0].children;
    expect(paragraphChildren.filter((child) => child.type === 'link').map((link) => link.url))
      .toEqual(['#call-15', '#call-16']);
  });
});
