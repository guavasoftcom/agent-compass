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
import { parseTaskNotificationEnvelope, promptSummaryRenderer } from './promptSummary';

describe('promptSummaryRenderer', () => {
  it('extracts the <summary> text from a real task-notification envelope', () => {
    const prompt = [
      '<task-notification>',
      '<task-id>a176ba9b9d4d55fc0</task-id>',
      '<tool-use-id>toolu_01UDxemrcp32b2Vf4AMBE3rF</tool-use-id>',
      '<status>completed</status>',
      '<summary>Agent "Angle E: simplification check" finished</summary>',
      '<note>A task-notification fires each time this agent stops...</note>',
      '</task-notification>',
    ].join('\n');
    expect(promptSummaryRenderer(prompt)).toBe(
      'Agent "Angle E: simplification check" finished',
    );
  });

  it('tolerates leading whitespace before the envelope tag', () => {
    const prompt = '\n\n  <task-notification>\n<summary>Agent "X" finished</summary>\n</task-notification>';
    expect(promptSummaryRenderer(prompt)).toBe('Agent "X" finished');
  });

  it('falls back to a generic label when the envelope has no <summary>', () => {
    const prompt = '<task-notification>\n<task-id>abc</task-id>\n</task-notification>';
    expect(promptSummaryRenderer(prompt)).toBe('Subagent task notification');
  });

  it('returns null for an ordinary prompt', () => {
    expect(promptSummaryRenderer('Refactor the auth middleware')).toBeNull();
  });

  // The exact case this guards: a real human message that happens to paste a
  // task-notification block as an example, further into the text, must not
  // be mistaken for an actual notification turn — only an envelope the
  // prompt itself *opens* with counts.
  it('returns null when the envelope appears later in a real message rather than at the start', () => {
    const prompt =
      "I've noticed some prompts come from an agent calling a subagent. e.g.:\n\n<task-notification>\n<summary>Agent \"X\" finished</summary>\n</task-notification>";
    expect(promptSummaryRenderer(prompt)).toBeNull();
  });

  it('matches the summary non-greedily across multiple envelopes', () => {
    const prompt =
      '<task-notification><summary>first</summary></task-notification><task-notification><summary>second</summary></task-notification>';
    expect(promptSummaryRenderer(prompt)).toBe('first');
  });
});

describe('parseTaskNotificationEnvelope', () => {
  it('splits structural fields from summary/result/note prose', () => {
    const prompt = [
      '<task-notification>',
      '<task-id>a176ba9b9d4d55fc0</task-id>',
      '<tool-use-id>toolu_01UDxemrcp32b2Vf4AMBE3rF</tool-use-id>',
      '<status>completed</status>',
      '<summary>Agent "Angle E: simplification check" finished</summary>',
      '<result>## Findings\n\n- Nothing to simplify</result>',
      '<note>A task-notification fires each time this agent stops.</note>',
      '</task-notification>',
    ].join('\n');
    expect(parseTaskNotificationEnvelope(prompt)).toEqual({
      fields: {
        'task-id': 'a176ba9b9d4d55fc0',
        'tool-use-id': 'toolu_01UDxemrcp32b2Vf4AMBE3rF',
        status: 'completed',
      },
      summary: 'Agent "Angle E: simplification check" finished',
      result: '## Findings\n\n- Nothing to simplify',
      note: 'A task-notification fires each time this agent stops.',
    });
  });

  it('returns null prose fields when absent', () => {
    const prompt = '<task-notification>\n<task-id>abc</task-id>\n</task-notification>';
    expect(parseTaskNotificationEnvelope(prompt)).toEqual({
      fields: { 'task-id': 'abc' },
      summary: null,
      result: null,
      note: null,
    });
  });

  it('returns null for an ordinary prompt', () => {
    expect(parseTaskNotificationEnvelope('Refactor the auth middleware')).toBeNull();
  });

  it('returns null when the envelope appears later in the text rather than at the start', () => {
    const prompt =
      'Example:\n\n<task-notification>\n<summary>X</summary>\n</task-notification>';
    expect(parseTaskNotificationEnvelope(prompt)).toBeNull();
  });

  it('parses only the first envelope when multiple are concatenated', () => {
    const prompt =
      '<task-notification><task-id>1</task-id><summary>first</summary></task-notification><task-notification><task-id>2</task-id><summary>second</summary></task-notification>';
    expect(parseTaskNotificationEnvelope(prompt)).toEqual({
      fields: { 'task-id': '1' },
      summary: 'first',
      result: null,
      note: null,
    });
  });
});
