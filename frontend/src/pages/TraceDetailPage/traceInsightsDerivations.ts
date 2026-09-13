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

// Tool-call classification taxonomy — a READ/EDIT/SEARCH/VERIFY/OTHER kind per tool name, paired
// with the palette index each kind maps to. Shared by AnalyzeTraceDialog/summarizeTraceWork.ts
// (classifies each Tools/Files chip) and AnalyzeTraceDialogView.tsx (reads PHASE_KIND_COLOR_INDEX
// to color those chips) so a Read/Edit/Search/Bash chip takes a consistent hue everywhere it
// appears on the Analyze Trace dialog's summary card. Pure, no React. This was extracted from a
// since-removed phase-timeline feature and kept purely for that reuse — see this page's own
// CLAUDE.md for the history.

export type PhaseKind = 'READ' | 'EDIT' | 'SEARCH' | 'VERIFY' | 'OTHER';

// Color indices for each kind: maps to colorForIndex() from theme/theme.ts
export const PHASE_KIND_COLOR_INDEX: Record<PhaseKind, number> = {
  READ: 0,
  EDIT: 1,
  SEARCH: 2,
  VERIFY: 3,
  OTHER: 4,
};

export const classifyToolCall = (toolName: string | undefined): PhaseKind => {
  if (!toolName) {
    return 'OTHER';
  }

  const lower = toolName.toLowerCase();

  if (lower === 'read') {
    return 'READ';
  }
  if (lower === 'edit' || lower === 'write') {
    return 'EDIT';
  }
  if (lower === 'grep' || lower === 'glob') {
    return 'SEARCH';
  }
  if (lower === 'bash') {
    return 'VERIFY';
  }

  return 'OTHER';
};
