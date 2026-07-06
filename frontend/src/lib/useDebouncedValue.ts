import { useEffect, useState } from 'react';

// Shared debounce delay for free-text search boxes (Logs, Traces) that feed a
// `filters` object keyed into TanStack Query. Typing without this delay re-keys
// `filters` on every keystroke, resetting the stream and refetching the
// histogram/facets/table on every character.
export const SEARCH_DEBOUNCE_MS = 250;

/**
 * Returns a debounced copy of `value` that only updates after `delayMs` has
 * elapsed without `value` changing. The caller keeps the immediate `value`
 * bound to the controlled input (so typing stays responsive) and feeds this
 * debounced copy into whatever derives the query key instead.
 */
export const useDebouncedValue = <ValueType>(
  value: ValueType,
  delayMs: number = SEARCH_DEBOUNCE_MS,
): ValueType => {
  const [debouncedValue, setDebouncedValue] = useState(value);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => {
      setDebouncedValue(value);
    }, delayMs);
    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [value, delayMs]);

  return debouncedValue;
};
