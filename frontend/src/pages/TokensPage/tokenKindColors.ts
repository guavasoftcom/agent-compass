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
import { colorForIndex } from '../../theme/theme';

export type TokenKind = 'cacheRead' | 'input' | 'cacheCreation' | 'output';

/**
 * One color per token kind, shared by the Overview tab's donut
 * (`TokensPageView`'s `mixSlices`), its trend chart (`series`), and the
 * cache-efficiency detail dialog's token bar — so a kind like "Input" never
 * wears two different colors on the same page.
 *
 * The assignment follows the donut's original ordering (cache read, input,
 * cache creation, output). Before this constant existed, the trend chart
 * assigned `colorForIndex` by array position in its own `[Cache read, Cache
 * creation, Input, Output]` series order instead of by kind, which swapped
 * cache creation and input relative to the donut. Read from here rather than
 * calling `colorForIndex` directly for any per-kind token color.
 */
export const TOKEN_KIND_COLORS: Record<TokenKind, string> = {
  cacheRead: colorForIndex(0),
  input: colorForIndex(1),
  cacheCreation: colorForIndex(2),
  output: colorForIndex(3),
};

/** Display label per token kind, shared alongside TOKEN_KIND_COLORS. */
export const TOKEN_KIND_LABELS: Record<TokenKind, string> = {
  cacheRead: 'Cache read',
  input: 'Input',
  cacheCreation: 'Cache creation',
  output: 'Output',
};
