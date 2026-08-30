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
