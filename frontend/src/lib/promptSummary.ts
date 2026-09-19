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
const TASK_NOTIFICATION_SUMMARY_PATTERN = /<summary>([\s\S]*?)<\/summary>/;

// Renders a short, human-readable summary for a prompt that isn't really
// human-authored text — currently just the <task-notification> envelope the
// harness delivers when a background subagent finishes (see its format:
// <task-id>, <tool-use-id>, <status>, <summary>, ...), detected by its exact
// opening tag and summarized from its own <summary> tag. A generic name and
// entry point on purpose: other non-authored prompt shapes that need the same
// "don't show this raw" treatment belong here as additional cases, not as
// separate one-off detectors scattered across call sites. Returns null for an
// ordinary, human-authored prompt.
export const promptSummaryRenderer = (prompt: string): string | null => {
  if (!prompt.trimStart().startsWith('<task-notification>')) {
    return null;
  }
  const summaryMatch = TASK_NOTIFICATION_SUMMARY_PATTERN.exec(prompt);
  return summaryMatch ? summaryMatch[1].trim() : 'Subagent task notification';
};

const TASK_NOTIFICATION_ENVELOPE_PATTERN =
  /^<task-notification>([\s\S]*?)<\/task-notification>/;
// Envelope tags are flat (no tag nests inside another), so a single
// non-greedy match per tag is exact rather than heuristic.
const TASK_NOTIFICATION_FIELD_PATTERN = /<([a-z][a-z-]*)>([\s\S]*?)<\/\1>/g;

export interface TaskNotificationEnvelope {
  /** Every envelope tag except `summary`/`result`/`note`, in the order they
   *  appeared (e.g. `task-id`, `tool-use-id`, `status`, `output-file`) —
   *  structural metadata, not prose, meant for a key/value display. */
  fields: Record<string, string>;
  summary: string | null;
  /** The dispatched task's own answer/output — markdown-formatted prose from
   *  whatever ran (a subagent's final report, a background command's
   *  findings), distinct from `summary`'s one-line status line. */
  result: string | null;
  note: string | null;
}

// Full structured parse of a <task-notification> envelope, for a caller that
// wants to render more than promptSummaryRenderer's one-line summary — e.g. a
// "view full prompt" dialog that would otherwise dump the raw XML. Returns
// null under the same "must open with the exact tag" rule promptSummaryRenderer
// uses, so a human message that merely pastes an envelope further in still
// isn't misparsed. Pure, no React.
export const parseTaskNotificationEnvelope = (
  prompt: string,
): TaskNotificationEnvelope | null => {
  const envelopeMatch = TASK_NOTIFICATION_ENVELOPE_PATTERN.exec(
    prompt.trimStart(),
  );
  if (!envelopeMatch) {
    return null;
  }
  const fields: Record<string, string> = {};
  let summary: string | null = null;
  let result: string | null = null;
  let note: string | null = null;
  for (const [, tag, value] of envelopeMatch[1].matchAll(
    TASK_NOTIFICATION_FIELD_PATTERN,
  )) {
    const trimmedValue = value.trim();
    if (tag === 'summary') {
      summary = trimmedValue;
    } else if (tag === 'result') {
      result = trimmedValue;
    } else if (tag === 'note') {
      note = trimmedValue;
    } else {
      fields[tag] = trimmedValue;
    }
  }
  return { fields, summary, result, note };
};
