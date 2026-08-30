import { describe, expect, it } from 'vitest';
import {
  buildModelMix,
  buildTrendSeries,
  CATEGORY_LABELS,
  categoryColorIndex,
  inFixedCategoryOrder,
} from './costDerivations';
import type { CostModelEffortCell, CostTrendPoint } from '../../api';

describe('categoryColorIndex', () => {
  it('assigns a fixed order independent of rank', () => {
    expect(categoryColorIndex('MAIN_LOOP')).toBe(0);
    expect(categoryColorIndex('SUBAGENT')).toBe(1);
    expect(categoryColorIndex('SKILL')).toBe(2);
    expect(categoryColorIndex('AUXILIARY')).toBe(3);
  });
});

describe('inFixedCategoryOrder', () => {
  it('reorders rows into MAIN_LOOP, SUBAGENT, SKILL, AUXILIARY regardless of input order', () => {
    const rows = [
      { category: 'AUXILIARY' as const, value: 1 },
      { category: 'MAIN_LOOP' as const, value: 2 },
      { category: 'SKILL' as const, value: 3 },
      { category: 'SUBAGENT' as const, value: 4 },
    ];

    expect(inFixedCategoryOrder(rows).map((row) => row.category)).toEqual([
      'MAIN_LOOP',
      'SUBAGENT',
      'SKILL',
      'AUXILIARY',
    ]);
  });
});

describe('buildTrendSeries', () => {
  it('builds one series per category in fixed order, aligned to the trend buckets', () => {
    const trend: CostTrendPoint[] = [
      { timestamp: '2026-08-01T00:00:00Z', costByCategory: { MAIN_LOOP: 10, SUBAGENT: 2 } },
      { timestamp: '2026-08-02T00:00:00Z', costByCategory: { MAIN_LOOP: 5 } },
    ];

    const { axisDates, series } = buildTrendSeries(trend);

    expect(axisDates).toHaveLength(2);
    expect(series.map((s) => s.label)).toEqual([
      CATEGORY_LABELS.MAIN_LOOP,
      CATEGORY_LABELS.SUBAGENT,
      CATEGORY_LABELS.SKILL,
      CATEGORY_LABELS.AUXILIARY,
    ]);

    const mainLoop = series.find((s) => s.label === CATEGORY_LABELS.MAIN_LOOP);
    expect(mainLoop?.data).toEqual([10, 5]);

    // SUBAGENT had no entry in the second bucket -- must fill 0, not undefined/NaN.
    const subagent = series.find((s) => s.label === CATEGORY_LABELS.SUBAGENT);
    expect(subagent?.data).toEqual([2, 0]);

    const skill = series.find((s) => s.label === CATEGORY_LABELS.SKILL);
    expect(skill?.data).toEqual([0, 0]);
  });

  it('returns empty axis and series for an empty trend', () => {
    const { axisDates, series } = buildTrendSeries([]);
    expect(axisDates).toEqual([]);
    expect(series).toHaveLength(4);
    series.forEach((s) => expect(s.data).toEqual([]));
  });
});

describe('buildModelMix', () => {
  const cell = (overrides: Partial<CostModelEffortCell>): CostModelEffortCell => ({
    model: 'claude-sonnet-5',
    effort: null,
    costUsd: 0,
    requests: 0,
    inputTokens: 0,
    outputTokens: 0,
    cacheCreationTokens: 0,
    cacheReadTokens: 0,
    ...overrides,
  });

  it('groups (model, effort) cells by model, summing cost, ranked cost-descending', () => {
    const cells: CostModelEffortCell[] = [
      cell({ model: 'claude-opus-5', effort: 'high', costUsd: 100 }),
      cell({ model: 'claude-sonnet-5', effort: 'high', costUsd: 20 }),
      cell({ model: 'claude-opus-5', effort: 'medium', costUsd: 50 }),
      cell({ model: 'claude-sonnet-5', effort: null, costUsd: 30 }),
    ];

    const mix = buildModelMix(cells);

    expect(mix).toEqual([
      { model: 'claude-opus-5', costUsd: 150, colorIndex: 0 },
      { model: 'claude-sonnet-5', costUsd: 50, colorIndex: 1 },
    ]);
  });

  it('returns an empty array for no cells', () => {
    expect(buildModelMix([])).toEqual([]);
  });
});
