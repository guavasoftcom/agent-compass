// Renders any attribute value as a display string: objects become JSON, nullish
// values become their literal text ('null' / 'undefined'), everything else String()s.
export const attrValueAsString = (value: unknown): string => {
  if (value == null) {
    return String(value);
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
};
