import { describe, expect, it } from 'vitest';
import { colorForIndex } from '../../theme/theme';
import {
  buildModelColorIndexes,
  buildModelCoverageModels,
  buildModelFirstBlocks,
  withShare,
} from './skillsAgentsDerivations';
import type { IdentifierUsageRow } from '../../api';

const OPUS = 'claude-opus-4-8';
const SONNET = 'claude-sonnet-4-6';
const HAIKU = 'claude-haiku-4-5-20251001';
const MYSTERY = 'claude-mystery-9';

const skillRows: IdentifierUsageRow[] = [
  { tool: 'sync-docs', calls: 75, byModel: { [SONNET]: 40, [OPUS]: 28, [HAIKU]: 7 } },
  { tool: 'ship', calls: 49, byModel: { [HAIKU]: 49 } },
  { tool: 'claude-api', calls: 13, byModel: { [OPUS]: 13 } },
  { tool: 'artifact-design', calls: 9, byModel: { [OPUS]: 9 } },
];

const subagentRows: IdentifierUsageRow[] = [
  { tool: 'claude-code-guide', calls: 4, byModel: { [OPUS]: 1, [HAIKU]: 3 } },
  { tool: 'Explore', calls: 4, byModel: { [SONNET]: 2, [HAIKU]: 2 } },
  { tool: 'unknown', calls: 1, byModel: { [SONNET]: 1 } },
];

describe('withShare', () => {
  it('computes each row share against the total', () => {
    const { rows, total } = withShare(skillRows);

    expect(total).toBe(146);
    expect(rows[0].share).toBeCloseTo((75 / 146) * 100);
    expect(rows[1].share).toBeCloseTo((49 / 146) * 100);
  });

  it('reports zero share instead of dividing by zero on an empty window', () => {
    const { rows, total } = withShare([{ tool: 'ship', calls: 0, byModel: {} }]);

    expect(total).toBe(0);
    expect(rows[0].share).toBe(0);
  });
});

describe('buildModelColorIndexes', () => {
  it('orders the known model families Sonnet, Opus, Haiku regardless of call volume', () => {
    const indexes = buildModelColorIndexes(skillRows, subagentRows);

    // Opus has the most calls overall (28+13+9+1=51), but the family order is
    // fixed — Sonnet still gets index 0 (the violet aurora slot).
    expect([...indexes.entries()]).toEqual([[SONNET, 0], [OPUS, 1], [HAIKU, 2]]);
  });

  it('places an unrecognized model family after the known trio, ordered by call volume', () => {
    const withMystery: IdentifierUsageRow[] = [
      { tool: 'ship', calls: 5, byModel: { [MYSTERY]: 5, [SONNET]: 1 } },
    ];

    const indexes = buildModelColorIndexes(withMystery);

    expect([...indexes.entries()]).toEqual([[SONNET, 0], [MYSTERY, 1]]);
  });

  it('tolerates rows whose byModel map is missing', () => {
    const withoutSplit = [{ tool: 'ship', calls: 3 }] as unknown as IdentifierUsageRow[];

    expect(buildModelColorIndexes(withoutSplit).size).toBe(0);
  });
});

describe('buildModelCoverageModels', () => {
  it('lists every known model in palette order with a short label and colour', () => {
    const indexes = buildModelColorIndexes(skillRows, subagentRows);

    expect(buildModelCoverageModels(indexes)).toEqual([
      { key: SONNET, label: 'Sonnet 4 6', color: colorForIndex(0) },
      { key: OPUS, label: 'Opus 4 8', color: colorForIndex(1) },
      { key: HAIKU, label: 'Haiku 4 5 20251001', color: colorForIndex(2) },
    ]);
  });
});

describe('buildModelFirstBlocks', () => {
  it('gives every known model a block, ranked by that model\'s own calls', () => {
    const indexes = buildModelColorIndexes(skillRows, subagentRows);
    const blocks = buildModelFirstBlocks(skillRows, indexes);

    expect(blocks.map((block) => block.model)).toEqual([SONNET, OPUS, HAIKU]);

    const sonnetBlock = blocks[0];
    expect(sonnetBlock.totalCalls).toBe(40);
    expect(sonnetBlock.rows).toEqual([{ identifier: 'sync-docs', calls: 40, muted: false }]);

    const opusBlock = blocks[1];
    expect(opusBlock.totalCalls).toBe(50);
    expect(opusBlock.rows).toEqual([
      { identifier: 'claude-api', calls: 13, muted: false },
      { identifier: 'artifact-design', calls: 9, muted: false },
      { identifier: 'sync-docs', calls: 28, muted: false },
    ].sort((left, right) => right.calls - left.calls));

    const haikuBlock = blocks[2];
    expect(haikuBlock.totalCalls).toBe(56);
    expect(haikuBlock.rows[0]).toEqual({ identifier: 'ship', calls: 49, muted: false });
  });

  it('renders an empty-rows block (not an omitted block) for a model with zero calls in this row set', () => {
    const onlyOpus: IdentifierUsageRow[] = [{ tool: 'ship', calls: 4, byModel: { [OPUS]: 4 } }];
    const indexes = buildModelColorIndexes(onlyOpus, [
      { tool: 'other', calls: 1, byModel: { [SONNET]: 1, [HAIKU]: 1 } },
    ]);

    const blocks = buildModelFirstBlocks(onlyOpus, indexes);
    const sonnetBlock = blocks.find((block) => block.model === SONNET);

    expect(sonnetBlock).toEqual({ model: SONNET, label: 'Sonnet 4 6', color: colorForIndex(0), totalCalls: 0, rows: [] });
  });

  it('marks the unknown bucket as muted', () => {
    const indexes = buildModelColorIndexes(subagentRows);
    const blocks = buildModelFirstBlocks(subagentRows, indexes);
    const sonnetBlock = blocks.find((block) => block.model === SONNET);

    expect(sonnetBlock?.rows).toContainEqual({ identifier: 'unknown', calls: 1, muted: true });
  });

  it('gives every block the same colour for the same model as buildModelCoverageModels', () => {
    const indexes = buildModelColorIndexes(skillRows, subagentRows);
    const coverageModels = buildModelCoverageModels(indexes);
    const skillBlocks = buildModelFirstBlocks(skillRows, indexes);

    for (const model of coverageModels) {
      const block = skillBlocks.find((candidate) => candidate.model === model.key);
      expect(block?.color).toBe(model.color);
    }
  });

  it('yields empty-rows blocks when the backend sends no split at all', () => {
    const withoutSplit = [{ tool: 'ship', calls: 3 }] as unknown as IdentifierUsageRow[];

    expect(buildModelFirstBlocks(withoutSplit, new Map())).toEqual([]);
  });
});
