export const NANOS_PER_MILLI = 1_000_000;
export const MS_PER_SECOND = 1000;

export const formatDuration = (nanos: number | null | undefined): string => {
  if (nanos == null) {
    return '';
  }
  const ms = nanos / NANOS_PER_MILLI;
  if (ms >= MS_PER_SECOND) {
    return `${(ms / MS_PER_SECOND).toFixed(2)} s`;
  }
  return `${ms.toFixed(1)} ms`;
};
