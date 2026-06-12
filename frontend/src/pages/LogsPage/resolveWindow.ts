import { WINDOWS } from '../../constants';
import type { WindowSelection } from '../../api';

const MS_PER_MINUTE = 60_000;

export interface ResolvedWindow {
  startTimestamp: string;
  endTimestamp: string;
  label: string;
}

/**
 * Resolve a WindowSelection into concrete ISO timestamps at the moment of call.
 *
 * For presets: keeps a 1-minute future buffer on the end (clock skew / freshest rows),
 * but anchors the start to that end so the span is exactly `minutes` wide — never
 * wider. Padding both ends would make the "30 days" preset 30d+1m, tripping the
 * backend's 30-day ValidDateRange cap and 400-ing every Logs endpoint.
 *
 * Lives in its own module so both LogsPage (container) and LogsPageView (view) can
 * import it without a circular dependency, and so that neither file triggers the
 * react-refresh/only-export-components lint rule by mixing helper exports with components.
 */
export const resolveWindow = (selection: WindowSelection): ResolvedWindow => {
  if (selection.kind === 'custom') {
    return {
      startTimestamp: selection.startTimestamp,
      endTimestamp: selection.endTimestamp,
      label: 'selected range',
    };
  }
  const now = Date.now();
  const label = WINDOWS.find((w) => w.value === selection.minutes)?.label ?? 'window';
  // Keep a 1-minute future buffer on the end (clock skew / freshest rows), but anchor
  // the start to that end so the span is exactly `minutes` wide — never wider. Padding
  // both ends would make the "30 days" preset 30d+1m, tripping the backend's 30-day
  // ValidDateRange cap and 400-ing every Logs endpoint.
  const endMs = now + MS_PER_MINUTE;
  return {
    startTimestamp: new Date(endMs - selection.minutes * MS_PER_MINUTE).toISOString(),
    endTimestamp: new Date(endMs).toISOString(),
    label,
  };
};
