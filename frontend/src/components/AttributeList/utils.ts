import { jsonrepair } from 'jsonrepair';
import type { ParsedJson } from './types';

export type { ParsedJson };

const TRUNCATION_MARKER_PATTERN = /\s*\[[^\]]*truncat[^\]]*\]\s*$/i;

export const formatAttrValue = (value: unknown): string => {
  if (value == null) {
    return String(value);
  }
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'object') {
    return JSON.stringify(value, null, 2);
  }
  return String(value);
};

export const tryParseJson = (text: string): ParsedJson | undefined => {
  const stripped = text.trim().replace(TRUNCATION_MARKER_PATTERN, '');
  const markerStripped = stripped.length !== text.trim().length;
  if (!stripped.startsWith('{') && !stripped.startsWith('[')) {
    return undefined;
  }
  try {
    return { value: JSON.parse(stripped), repaired: markerStripped };
  } catch {
    try {
      return { value: JSON.parse(jsonrepair(stripped)), repaired: true };
    } catch {
      return undefined;
    }
  }
};

export const isPlainObject = (value: unknown): value is Record<string, unknown> => {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    Object.getPrototypeOf(value) === Object.prototype
  );
};
