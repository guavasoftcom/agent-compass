import { describe, expect, it } from 'vitest';
import { colorForIndex } from '../../theme/theme';
import {
  buildModelColorIndexes,
  buildModelLegendItems,
  toModelBreakdownRows,
  withShare,
} from './skillsAgentsDerivations';
import type { IdentifierUsageRow } from '../../api';

const OPUS = 'claude-opus-4-8';
const SONNET = 'claude-sonnet-4-6';
const HAIKU = 'claude-haiku-4-5-20251001';

const skillRows: IdentifierUsageRow[] = [
  { tool: 'ship', calls: 30, byModel: { [SONNET]: 20, [OPUS]: 10 } },
  { tool: 'verify', calls: 10, byModel: { [OPUS]: 6, [HAIKU]: 4 } },
];

const subagentRows: IdentifierUsageRow[] = [
  { tool: 'Explore', calls: 12, byModel: { [OPUS]: 12 } },
];

describe('withShare', () => {
  it('computes each row share against the total', () => {
    const { rows, total } = withShare(skillRows);

    expect(total).toBe(40);
    expect(rows[0].share).toBeCloseTo(75);
    expect(rows[1].share).toBeCloseTo(25);
  });

  it('reports zero share instead of dividing by zero on an empty window', () => {
    const { rows, total } = withShare([{ tool: 'ship', calls: 0, byModel: {} }]);

    expect(total).toBe(0);
    expect(rows[0].share).toBe(0);
  });
});

describe('buildModelColorIndexes', () => {
  it('orders models by total calls across every row set', () => {
    const indexes = buildModelColorIndexes(skillRows, subagentRows);

    // Opus 28 across both sets, Sonnet 20, Haiku 4.
    expect([...indexes.entries()]).toEqual([[OPUS, 0], [SONNET, 1], [HAIKU, 2]]);
  });

  it('breaks ties on model id so the assignment is stable', () => {
    const tied: IdentifierUsageRow[] = [
      { tool: 'ship', calls: 2, byModel: { [SONNET]: 1, [OPUS]: 1 } },
    ];

    expect([...buildModelColorIndexes(tied).keys()]).toEqual([OPUS, SONNET]);
  });

  it('tolerates rows whose byModel map is missing', () => {
    const withoutSplit = [{ tool: 'ship', calls: 3 }] as unknown as IdentifierUsageRow[];

    expect(buildModelColorIndexes(withoutSplit).size).toBe(0);
  });
});

describe('buildModelLegendItems', () => {
  it('lists only the models present in the given rows, in palette order', () => {
    const indexes = buildModelColorIndexes(skillRows, subagentRows);

    expect(buildModelLegendItems(subagentRows, indexes)).toEqual([
      { label: 'Opus 4 8', color: colorForIndex(0) },
    ]);
    expect(buildModelLegendItems(skillRows, indexes).map((item) => item.label)).toEqual([
      'Opus 4 8',
      'Sonnet 4 6',
      'Haiku 4 5 20251001',
    ]);
  });
});

describe('toModelBreakdownRows', () => {
  it('keeps the row total and orders segments to match the legend', () => {
    const indexes = buildModelColorIndexes(skillRows, subagentRows);
    const rows = toModelBreakdownRows(skillRows, indexes);

    expect(rows[0].identifier).toBe('ship');
    expect(rows[0].totalCalls).toBe(30);
    expect(rows[0].segments).toEqual([
      { model: OPUS, label: 'Opus 4 8', calls: 10, color: colorForIndex(0) },
      { model: SONNET, label: 'Sonnet 4 6', calls: 20, color: colorForIndex(1) },
    ]);
  });

  it('gives every row the same colour for the same model', () => {
    const indexes = buildModelColorIndexes(skillRows, subagentRows);
    const [skillRow] = toModelBreakdownRows(skillRows, indexes);
    const [subagentRow] = toModelBreakdownRows(subagentRows, indexes);

    const opusInSkill = skillRow.segments.find((segment) => segment.model === OPUS);
    const opusInSubagent = subagentRow.segments.find((segment) => segment.model === OPUS);
    expect(opusInSkill?.color).toBe(opusInSubagent?.color);
  });

  it('yields a row with no segments when the backend sends no split', () => {
    const withoutSplit = [{ tool: 'ship', calls: 3 }] as unknown as IdentifierUsageRow[];

    expect(toModelBreakdownRows(withoutSplit, new Map())).toEqual([
      { identifier: 'ship', totalCalls: 3, segments: [] },
    ]);
  });
});
