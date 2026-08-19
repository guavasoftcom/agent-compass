// Pure derivations for the Skills & Subagents page. Kept out of the container so
// the share maths and the model-colour assignment can be unit-tested without
// mounting the page.

import type { IdentifierUsageRow } from '../../api';
import { colorForIndex } from '../../theme/theme';
import { shortModelName } from '../../lib/format';
import type { DonutCoverageModel } from '../../components/DonutCard';
import type { ModelFirstBlock, ModelFirstBlockRow } from './components/ModelFirstBlocks';

export type IdentifierRowWithShare = IdentifierUsageRow & { share: number };

export interface IdentifierRowsWithTotal {
  rows: IdentifierRowWithShare[];
  total: number;
}

/** Identifier bucket for calls the backend couldn't attribute to a real skill/subagent. */
export const UNKNOWN_IDENTIFIER = 'unknown';

export const withShare = (rows: IdentifierUsageRow[]): IdentifierRowsWithTotal => {
  const total = rows.reduce((sum, row) => sum + row.calls, 0);
  const enriched: IdentifierRowWithShare[] = rows.map((row) => ({
    ...row,
    share: total === 0 ? 0 : (100 * row.calls) / total,
  }));
  return { rows: enriched, total };
};

// The app's aurora trio is reserved for these three model families, in this
// fixed left-to-right order, regardless of which one made the most calls in
// the window — ties the by-model blocks and the mix-legend coverage ticks
// back to the product's own signature hues instead of a volume-ranked
// categorical palette. A model outside this set still gets a stable colour
// (falls back to call-volume ordering, starting after the trio) so a future
// 4th model doesn't crash the assignment, even though this hasn't been
// exercised past three.
const KNOWN_MODEL_FAMILY_ORDER = ['sonnet', 'opus', 'haiku'];

const modelFamily = (model: string): string => {
  const [family] = model.replace(/^claude-/, '').split('-');
  return (family ?? '').toLowerCase();
};

/**
 * Orders every model seen across the given row sets: known families
 * (Sonnet, Opus, Haiku) first in that fixed order, then any other model by
 * total calls descending. Returns model id → palette index. Both by-model
 * blocks and the mix-legend coverage ticks are coloured and ordered from the
 * same map, so one model keeps one colour and one position across the whole
 * page.
 */
export const buildModelColorIndexes = (
  ...rowSets: IdentifierUsageRow[][]
): Map<string, number> => {
  const callsByModel = new Map<string, number>();
  for (const rows of rowSets) {
    for (const row of rows) {
      for (const [model, calls] of Object.entries(row.byModel ?? {})) {
        callsByModel.set(model, (callsByModel.get(model) ?? 0) + calls);
      }
    }
  }

  const knownModels: string[] = [];
  const otherModels: string[] = [];
  for (const model of callsByModel.keys()) {
    if (KNOWN_MODEL_FAMILY_ORDER.includes(modelFamily(model))) {
      knownModels.push(model);
    } else {
      otherModels.push(model);
    }
  }

  knownModels.sort((left, right) => {
    const familyOrder =
      KNOWN_MODEL_FAMILY_ORDER.indexOf(modelFamily(left))
      - KNOWN_MODEL_FAMILY_ORDER.indexOf(modelFamily(right));
    return familyOrder !== 0 ? familyOrder : left.localeCompare(right);
  });
  otherModels.sort((left, right) => {
    const callsOrder = (callsByModel.get(right) ?? 0) - (callsByModel.get(left) ?? 0);
    return callsOrder !== 0 ? callsOrder : left.localeCompare(right);
  });

  const orderedModels = [...knownModels, ...otherModels];
  return new Map(orderedModels.map((model, index) => [model, index]));
};

/**
 * The fixed model list shared by `DonutCard`'s `coverageTicks` (per-row tick
 * group) and `legendCaption` (the colour key below the legend) — every model
 * known on the page, in palette order, regardless of whether it appears in
 * this particular row set. Both by-model cards' block order also follows
 * this same sequence.
 */
export const buildModelCoverageModels = (
  modelColorIndexes: Map<string, number>,
): DonutCoverageModel[] =>
  [...modelColorIndexes.entries()]
    .sort((left, right) => left[1] - right[1])
    .map(([model, index]) => ({
      key: model,
      label: shortModelName(model),
      color: colorForIndex(index),
    }));

/**
 * Turns usage rows into the model-first blocks the "Skills by model" /
 * "Subagents by model" card renders: one block per known model (in the same
 * fixed order as `buildModelCoverageModels`), each ranking only the rows that
 * model actually called, highest first. A model with zero calls anywhere in
 * this row set still gets a block (with `rows: []`) so the card can render
 * its "No calls in this window" line rather than omitting the model.
 */
export const buildModelFirstBlocks = (
  rows: IdentifierUsageRow[],
  modelColorIndexes: Map<string, number>,
): ModelFirstBlock[] =>
  [...modelColorIndexes.entries()]
    .sort((left, right) => left[1] - right[1])
    .map(([model, index]) => {
      const blockRows: ModelFirstBlockRow[] = rows
        .map((row) => ({
          identifier: row.tool,
          calls: row.byModel?.[model] ?? 0,
          muted: row.tool === UNKNOWN_IDENTIFIER,
        }))
        .filter((row) => row.calls > 0)
        .sort((left, right) => right.calls - left.calls);

      const totalCalls = blockRows.reduce((sum, row) => sum + row.calls, 0);

      return {
        model,
        label: shortModelName(model),
        color: colorForIndex(index),
        totalCalls,
        rows: blockRows,
      };
    });
