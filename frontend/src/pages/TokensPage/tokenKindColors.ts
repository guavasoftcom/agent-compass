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
