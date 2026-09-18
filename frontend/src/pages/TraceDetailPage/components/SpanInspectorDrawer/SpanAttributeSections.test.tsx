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
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../../test/renderWithProviders';
import SpanAttributeSections from './SpanAttributeSections';

// Attribute keys never collide with the (numeric) values used in these fixtures, so comparing
// where each key string first appears in the rendered text is a reliable, DOM-shape-agnostic way
// to assert on row order.
const orderOf = (container: HTMLElement, keys: string[]): string[] =>
  [...keys].sort((left, right) => container.textContent!.indexOf(left) - container.textContent!.indexOf(right));

describe('SpanAttributeSections', () => {
  it('sorts the Attributes section rows alphabetically by key, not by emission order', () => {
    const { container } = renderWithProviders(
      <SpanAttributeSections attributes={{ zeta: 1, alpha: 2, mu: 3 }} />,
    );

    expect(orderOf(container, ['zeta', 'alpha', 'mu'])).toEqual(['alpha', 'mu', 'zeta']);
  });

  it('sorts the Tool section rows alphabetically by key, independently of the Attributes section', () => {
    const { container } = renderWithProviders(
      <SpanAttributeSections
        attributes={{ tool_name: 'Bash', file_path: '/b', command: '/a', zeta: 1, alpha: 2 }}
      />,
    );

    expect(screen.getByText('Tool')).toBeInTheDocument();
    expect(screen.getByText('Attributes')).toBeInTheDocument();
    expect(orderOf(container, ['tool_name', 'file_path', 'command'])).toEqual([
      'command',
      'file_path',
      'tool_name',
    ]);
    expect(orderOf(container, ['zeta', 'alpha'])).toEqual(['alpha', 'zeta']);
  });
});
