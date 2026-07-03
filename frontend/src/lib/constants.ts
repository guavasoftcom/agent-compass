export interface WindowOption {
  value: number;
  label: string;
}

export const WINDOWS: readonly WindowOption[] = [
  { value: 5, label: '5 minutes' },
  { value: 15, label: '15 minutes' },
  { value: 30, label: '30 minutes' },
  { value: 60, label: '1 hour' },
  { value: 60 * 8, label: '8 hours' },
  { value: 60 * 24, label: '24 hours' },
  { value: 60 * 24 * 7, label: '7 days' },
  { value: 60 * 24 * 30, label: '30 days' },
];

// Rows-per-page options shared by every paged table footer (Sessions, Logs, Traces)
// so the choices stay in lockstep. The first entry is the default page size.
export const PAGE_SIZE_OPTIONS = [25, 50, 100] as const;

// Milliseconds-per-time-unit conversion factors, shared so the histogram bucket
// labels and the sample-data stores don't each redefine HOUR/DAY/MIN.
export const MS_PER_MINUTE = 60_000;
export const MS_PER_HOUR = 60 * MS_PER_MINUTE;
export const MS_PER_DAY = 24 * MS_PER_HOUR;

// Preset-only auto-refresh cadence, shared by every page's polling interval so
// the whole dashboard refetches on the same 60s beat.
export const AUTO_REFRESH_INTERVAL_MS = MS_PER_MINUTE;

// Live-tail poll cadence for the cursor-paged Stream views (Logs, Traces): how
// often each prepends genuinely new rows while auto-refresh is on.
export const TAIL_INTERVAL_MS = 1500;
