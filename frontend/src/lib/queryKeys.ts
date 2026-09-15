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
// Shared React Query cache-key fragment for a window + repository selection.
// Every window-scoped page folds its `WindowSelection` and the current
// `repositoryUrl` (from `lib/windowContext.tsx`) into a single string that
// goes straight into a `useQuery` key, so a stale cache entry from a
// different window or a different repository is never served across a
// selector change. Centralized here (rather than re-derived per page) so the
// two never drift the way TokensPage's operator-placement style once did.

import type { WindowSelection } from '../api';

export const buildWindowSelectionKey = (
  selection: WindowSelection,
  repositoryUrl: string | null,
): string =>
  (selection.kind === 'preset'
    ? `preset:${selection.minutes}`
    : `custom:${selection.startTimestamp}:${selection.endTimestamp}`) +
  `:repository:${repositoryUrl ?? 'all'}`;
