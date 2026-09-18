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
import type { SpanRow } from '../../api';
import { buildAgentDispatchColoring } from './agentDispatch';
import { buildSpanTree } from './spanTree';

const BASE_MS = Date.parse('2026-09-05T12:00:00.000Z');

const span = (
  spanId: string,
  parentSpanId: string | null,
  offsetSeconds: number,
  attributes: Record<string, unknown> = {},
): SpanRow =>
  ({
    spanId,
    parentSpanId,
    name: 'claude_code.tool',
    startTimestamp: new Date(BASE_MS + offsetSeconds * 1000).toISOString(),
    endTimestamp: new Date(BASE_MS + offsetSeconds * 1000 + 100).toISOString(),
    durationNanos: 100 * 1e6,
    statusCode: 'ok',
    attributes,
  }) as unknown as SpanRow;

const coloringFor = (spans: SpanRow[]) => buildAgentDispatchColoring(buildSpanTree(spans), spans);

describe('buildAgentDispatchColoring', () => {
  it('colors nothing and returns an empty legend when the trace dispatched no subagent', () => {
    const root = span('root', null, 0);
    const bash = span('bash', 'root', 1, { tool_name: 'Bash' });

    const coloring = coloringFor([root, bash]);

    expect(coloring.legend).toEqual([]);
    expect(coloring.colorBySpanId.size).toBe(0);
  });

  it('colors a dispatch span and its whole subtree, but not an unrelated sibling', () => {
    const root = span('root', null, 0);
    const dispatch = span('dispatch', 'root', 1, { tool_name: 'Agent', subagent_type: 'Explore' });
    const dispatchChild = span('dispatch-child', 'dispatch', 2, { tool_name: 'Read' });
    const sibling = span('sibling', 'root', 3, { tool_name: 'Bash' });

    const coloring = coloringFor([root, dispatch, dispatchChild, sibling]);

    const dispatchColor = coloring.colorBySpanId.get('dispatch');
    expect(dispatchColor).toBeDefined();
    expect(coloring.colorBySpanId.get('dispatch-child')).toBe(dispatchColor);
    expect(coloring.colorBySpanId.has('sibling')).toBe(false);
    expect(coloring.colorBySpanId.has('root')).toBe(false);
    expect(coloring.legend).toEqual([
      { label: 'Explore', color: dispatchColor, dispatchSpanIds: ['dispatch'] },
    ]);
    expect(coloring.labelBySpanId.get('dispatch')).toBe('Explore');
    expect(coloring.labelBySpanId.get('dispatch-child')).toBe('Explore');
    expect(coloring.labelBySpanId.has('sibling')).toBe(false);
    expect(coloring.labelBySpanId.has('root')).toBe(false);
  });

  it('gives two dispatches of the same subagent_type the same color and one legend entry ' +
    'listing both dispatch span ids in DFS order', () => {
    const root = span('root', null, 0);
    const first = span('first', 'root', 1, { tool_name: 'Agent', subagent_type: 'Explore' });
    const second = span('second', 'root', 2, { tool_name: 'Agent', subagent_type: 'Explore' });

    const coloring = coloringFor([root, first, second]);

    expect(coloring.colorBySpanId.get('first')).toBe(coloring.colorBySpanId.get('second'));
    expect(coloring.legend).toHaveLength(1);
    expect(coloring.legend[0].dispatchSpanIds).toEqual(['first', 'second']);
  });

  it('gives two dispatches of different subagent_type different colors', () => {
    const root = span('root', null, 0);
    const explore = span('explore', 'root', 1, { tool_name: 'Agent', subagent_type: 'Explore' });
    const plan = span('plan', 'root', 2, { tool_name: 'Agent', subagent_type: 'Plan' });

    const coloring = coloringFor([root, explore, plan]);

    expect(coloring.colorBySpanId.get('explore')).not.toBe(coloring.colorBySpanId.get('plan'));
    expect(coloring.legend.map((entry) => entry.label)).toEqual(['Explore', 'Plan']);
  });

  it('falls back to the generic label when subagent_type is unreadable, and still colors it', () => {
    const root = span('root', null, 0);
    const emptyType = span('empty-type', 'root', 1, { tool_name: 'Agent', subagent_type: '' });
    const missingType = span('missing-type', 'root', 2, { tool_name: 'Agent' });

    const coloring = coloringFor([root, emptyType, missingType]);

    expect(coloring.legend).toEqual([
      {
        label: 'Subagent',
        color: coloring.legend[0].color,
        dispatchSpanIds: ['empty-type', 'missing-type'],
      },
    ]);
    expect(coloring.colorBySpanId.get('empty-type')).toBe(coloring.colorBySpanId.get('missing-type'));
  });

  it('resolves a span under nested dispatches to the innermost one', () => {
    const root = span('root', null, 0);
    const outer = span('outer', 'root', 1, { tool_name: 'Agent', subagent_type: 'Plan' });
    const inner = span('inner', 'outer', 2, { tool_name: 'Agent', subagent_type: 'Explore' });
    const innerChild = span('inner-child', 'inner', 3, { tool_name: 'Read' });

    const coloring = coloringFor([root, outer, inner, innerChild]);

    const innerColor = coloring.colorBySpanId.get('inner');
    expect(coloring.colorBySpanId.get('outer')).not.toBe(innerColor);
    expect(coloring.colorBySpanId.get('inner-child')).toBe(innerColor);
    expect(coloring.labelBySpanId.get('outer')).toBe('Plan');
    expect(coloring.labelBySpanId.get('inner')).toBe('Explore');
    expect(coloring.labelBySpanId.get('inner-child')).toBe('Explore');
  });
});
