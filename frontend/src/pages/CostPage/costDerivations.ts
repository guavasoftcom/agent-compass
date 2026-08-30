// Pure derivations for the Cost page. Kept out of the container/view so the category
// ordering, colour assignment, and trend-series shaping can be unit-tested without
// mounting the page.

import type { CostCategory, CostModelEffortCell, CostTrendPoint } from '../../api';
import type { AreaTrendSeries } from '../../components/AreaTrendChart';
import { colorForIndex } from '../../theme/theme';

/**
 * Fixed left-to-right order for every category-keyed UI element on this page (the money
 * map, the trend chart's stacking order and legend, the drilldown accent colour) —
 * independent of the backend's cost-descending sort, so a category never changes colour
 * or position from one poll to the next just because its rank shifted.
 */
export const CATEGORY_ORDER: CostCategory[] = ['MAIN_LOOP', 'SUBAGENT', 'SKILL', 'AUXILIARY'];

/**
 * Shared caveat for every KPI on this page derived from `totalCostUsd` (Total spend, Burn
 * rate, Projected 30d, Cost per 1k tokens). Explains why this figure won't match the
 * similarly-named "Total cost" KPI on the Tokens page for the same window — the two read
 * from different pipelines that don't reconcile, not from a bug in either one.
 */
export const COST_SOURCE_INFO_TOOLTIP =
  'Based on the exact cost of each request, not the running cost counter used on the Tokens '
  + 'and Sessions pages. The two don\'t line up exactly — expect this total to read a few '
  + 'percent lower than "Total cost" on Tokens for the same window.';

export const CATEGORY_LABELS: Record<CostCategory, string> = {
  MAIN_LOOP: 'Main loop',
  SUBAGENT: 'Subagents',
  SKILL: 'Skills',
  AUXILIARY: 'Auxiliary',
};

/** Palette index for a category, fixed by `CATEGORY_ORDER` rather than by rank. */
export const categoryColorIndex = (category: CostCategory): number =>
  CATEGORY_ORDER.indexOf(category);

export const categoryColor = (category: CostCategory): string =>
  colorForIndex(categoryColorIndex(category));

/**
 * Sorts a category list into the fixed `CATEGORY_ORDER` rather than the backend's
 * cost-descending order, for every UI element where stable position matters more than
 * rank (the trend chart's stacking, the legend). The money map itself keeps the
 * backend's cost-descending order instead — see `CostPageView`.
 */
export const inFixedCategoryOrder = <T extends { category: CostCategory }>(rows: T[]): T[] =>
  [...rows].sort(
    (left, right) => CATEGORY_ORDER.indexOf(left.category) - CATEGORY_ORDER.indexOf(right.category),
  );

/**
 * Converts the backend's per-bucket `costByCategory` maps into one `AreaTrendSeries` per
 * category, in `CATEGORY_ORDER`, filling a bucket a category had zero spend in with `0`
 * (the backend omits it rather than sending an explicit zero — see `CostTrendPoint`'s
 * doc). Also returns the parsed axis dates so the container doesn't map the same
 * `trend` array twice.
 */
export const buildTrendSeries = (
  trend: CostTrendPoint[],
): { axisDates: Date[]; series: AreaTrendSeries[] } => {
  const axisDates = trend.map((point) => new Date(point.timestamp));
  const series = CATEGORY_ORDER.map((category) => ({
    label: CATEGORY_LABELS[category],
    data: trend.map((point) => point.costByCategory[category] ?? 0),
    color: categoryColor(category),
  }));
  return { axisDates, series };
};

/** One model's total spend, ranked cost-descending, with a palette index fixed by that rank. */
export interface ModelMixEntry {
  model: string;
  costUsd: number;
  /** `colorForIndex(colorIndex)` — the rank position, so a model's dot/slice color is the
   *  same everywhere on the "What drove it" tab regardless of how many (model, effort) rows
   *  it has in the cost-drivers grid. */
  colorIndex: number;
}

/**
 * Groups `CostBreakdown.modelEffort` cells (one row per (model, effort) pair) by model,
 * summing `costUsd`, and ranks the result cost-descending. The single source for both the
 * "What drove it" tab's Model mix donut and the Top model KPI, and the source of truth
 * `CostDriversCard` reads its per-row dot color from — a model must always wear the same
 * color whether it appears as a donut slice, a KPI dot, or a drivers-table row, no matter
 * how many effort levels it has.
 */
export const buildModelMix = (modelEffort: CostModelEffortCell[]): ModelMixEntry[] => {
  const totalsByModel = new Map<string, number>();
  for (const cell of modelEffort) {
    totalsByModel.set(cell.model, (totalsByModel.get(cell.model) ?? 0) + cell.costUsd);
  }
  return Array.from(totalsByModel.entries())
    .map(([model, costUsd]) => ({ model, costUsd }))
    .sort((left, right) => right.costUsd - left.costUsd)
    .map((entry, index) => ({ ...entry, colorIndex: index }));
};
