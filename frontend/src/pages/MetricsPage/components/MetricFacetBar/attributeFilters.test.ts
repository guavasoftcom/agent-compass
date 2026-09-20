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
import type { AttributeFilter } from '../../metricsApi';
import {
  applyFilterSelection,
  findFilterForKey,
  isFilterActive,
  removeFilterAt,
} from './attributeFilters';

const modelSonnet: AttributeFilter = { key: 'model', value: 'claude-sonnet-4' };
const modelOpus: AttributeFilter = { key: 'model', value: 'claude-opus-4' };
const terminalVscode: AttributeFilter = { key: 'terminal.type', value: 'vscode' };

describe('isFilterActive / findFilterForKey', () => {
  it('matches only the exact key = value pair', () => {
    const filters = [modelSonnet];

    expect(isFilterActive(filters, 'model', 'claude-sonnet-4')).toBe(true);
    expect(isFilterActive(filters, 'model', 'claude-opus-4')).toBe(false);
    expect(isFilterActive(filters, 'terminal.type', 'claude-sonnet-4')).toBe(false);
    expect(isFilterActive([], 'model', 'claude-sonnet-4')).toBe(false);
  });

  it('finds the chip for a key regardless of its value', () => {
    expect(findFilterForKey([terminalVscode, modelSonnet], 'model')).toEqual(modelSonnet);
    expect(findFilterForKey([terminalVscode], 'model')).toBeUndefined();
  });
});

describe('applyFilterSelection', () => {
  it('appends a filter for a new key, preserving the order of the existing ones', () => {
    expect(applyFilterSelection([modelSonnet], terminalVscode)).toEqual([modelSonnet, terminalVscode]);
    expect(applyFilterSelection([], modelSonnet)).toEqual([modelSonnet]);
  });

  it('replaces the chip for the same key in place instead of adding a second one', () => {
    const result = applyFilterSelection([modelSonnet, terminalVscode], modelOpus);

    expect(result).toEqual([modelOpus, terminalVscode]);
  });

  it('leaves the list unchanged when the exact pair is already active', () => {
    const filters = [modelSonnet, terminalVscode];

    expect(applyFilterSelection(filters, modelSonnet)).toEqual(filters);
  });

  it('never mutates its input', () => {
    const filters = [modelSonnet];

    applyFilterSelection(filters, modelOpus);
    applyFilterSelection(filters, terminalVscode);

    expect(filters).toEqual([modelSonnet]);
  });
});

describe('removeFilterAt', () => {
  it('removes only the chip at the index and keeps the rest', () => {
    expect(removeFilterAt([modelSonnet, terminalVscode], 0)).toEqual([terminalVscode]);
    expect(removeFilterAt([modelSonnet, terminalVscode], 1)).toEqual([modelSonnet]);
  });

  it('leaves the list unchanged for an out-of-range index', () => {
    expect(removeFilterAt([modelSonnet], 5)).toEqual([modelSonnet]);
    expect(removeFilterAt([], 0)).toEqual([]);
  });
});
