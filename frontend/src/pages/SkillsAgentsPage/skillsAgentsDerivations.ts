// Pure derivations for the Skills & Subagents page. Kept out of the container so
// the share maths and the model-colour assignment can be unit-tested without
// mounting the page.

import type { IdentifierUsageRow } from '../../api';
import { colorForIndex } from '../../theme/theme';
import { shortModelName } from '../../lib/format';
import type {
  ModelBreakdownRow,
  ModelBreakdownSegment,
} from './components/ModelBreakdownCard';
import type { ChartCardLegendItem } from '../../components/ChartCard';

export type IdentifierRowWithShare = IdentifierUsageRow & { share: number };

export interface IdentifierRowsWithTotal {
  rows: IdentifierRowWithShare[];
  total: number;
}

export const withShare = (rows: IdentifierUsageRow[]): IdentifierRowsWithTotal => {
  const total = rows.reduce((sum, row) => sum + row.calls, 0);
  const enriched: IdentifierRowWithShare[] = rows.map((row) => ({
    ...row,
    share: total === 0 ? 0 : (100 * row.calls) / total,
  }));
  return { rows: enriched, total };
};

/**
 * Orders every model seen across the given row sets by total calls descending
 * and returns model id → palette index. Both by-model cards are coloured from
 * the same map, so one model keeps one colour across the whole page (and its
 * legend order matches the segment order inside each bar).
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

  const orderedModels = [...callsByModel.entries()]
    .sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0]))
    .map(([model]) => model);

  return new Map(orderedModels.map((model, index) => [model, index]));
};

/**
 * Legend entries for a by-model card: every model that appears in `rows`, in the
 * shared palette order, labelled with its short display name.
 */
export const buildModelLegendItems = (
  rows: IdentifierUsageRow[],
  modelColorIndexes: Map<string, number>,
): ChartCardLegendItem[] => {
  const modelsInRows = new Set<string>();
  for (const row of rows) {
    for (const model of Object.keys(row.byModel ?? {})) {
      modelsInRows.add(model);
    }
  }

  return [...modelsInRows]
    .sort((left, right) => (modelColorIndexes.get(left) ?? 0) - (modelColorIndexes.get(right) ?? 0))
    .map((model) => ({
      label: shortModelName(model),
      color: colorForIndex(modelColorIndexes.get(model) ?? 0),
    }));
};

/**
 * Turns usage rows into the segmented-bar rows the by-model card renders.
 * Segments follow the shared palette order so every bar stacks its models in the
 * same left-to-right sequence as the legend. `byModel` sums are trusted over the
 * row's own `calls` only for the segment widths — the displayed total stays
 * `calls`, which is what the KPI tiles and the donut agree on.
 */
export const toModelBreakdownRows = (
  rows: IdentifierUsageRow[],
  modelColorIndexes: Map<string, number>,
): ModelBreakdownRow[] =>
  rows.map((row) => {
    const segments: ModelBreakdownSegment[] = Object.entries(row.byModel ?? {})
      .sort((left, right) => (modelColorIndexes.get(left[0]) ?? 0) - (modelColorIndexes.get(right[0]) ?? 0))
      .map(([model, calls]) => ({
        model,
        label: shortModelName(model),
        calls,
        color: colorForIndex(modelColorIndexes.get(model) ?? 0),
      }));

    return { identifier: row.tool, totalCalls: row.calls, segments };
  });
