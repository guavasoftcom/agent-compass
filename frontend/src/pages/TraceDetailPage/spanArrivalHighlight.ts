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

// How long a span that arrived while the trace was running stays highlighted in the waterfall.
// Shared by the row's fade-out animation and the view's timer that drops the span from the
// "newly arrived" set, so the two can't drift: the timer must not fire before the animation ends
// (the row would snap back mid-fade) and shouldn't linger long after (a row that remounts on
// collapse/zoom would replay the flash).
export const NEW_SPAN_HIGHLIGHT_MS = 2400;
