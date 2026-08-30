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
import { promptSummaryRenderer } from './promptSummary';

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
