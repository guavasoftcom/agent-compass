/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
import { jsonrepair } from 'jsonrepair';
import type { ParsedJson } from './types';

export type { ParsedJson };

// Match a trailing "[…truncated…]" marker WITHOUT letting the character classes
// cross a JSON bracket — `[^[\]]` (not `[^\]]`) stops the match from greedily
// swallowing an unclosed array (e.g. `"content":[ … `) along with the marker,
// which would otherwise destroy the partial payload before repair.
const TRUNCATION_MARKER_PATTERN = /\s*\[[^[\]]*truncat[^[\]]*\]\s*$/i;

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

// Last-resort repair for payloads that defeat jsonrepair — typically OTLP
// `body` attributes cut mid-string at the 60 KB cap, where the trailing `\`
// or embedded `{}` chars send jsonrepair off the rails. Walk the JSON
// character-by-character, find the last position outside of any open string
// where we crossed a `,`/`:`/bracket boundary, drop whatever partial key or
// trailing comma remains, then close every still-open `[`/`{` in reverse.
const closeTruncatedJson = (raw: string): string => {
  let inString = false;
  let escapeNext = false;
  let lastSafeEnd = 0;
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (escapeNext) {
      escapeNext = false;
      continue;
    }
    if (inString) {
      if (c === '\\') {
        if (i + 1 >= raw.length) {
          break;
        }
        escapeNext = true;
        continue;
      }
      if (c === '"') {
        inString = false;
        lastSafeEnd = i + 1;
        continue;
      }
      if (raw.charCodeAt(i) < 0x20) {
        break;
      }
      continue;
    }
    if (c === '"') {
      inString = true;
      continue;
    }
    if (c === '{' || c === '[' || c === '}' || c === ']') {
      lastSafeEnd = i + 1;
      continue;
    }
    if (c === ',' || c === ':') {
      lastSafeEnd = i + 1;
      continue;
    }
  }
  let prefix = raw.slice(0, lastSafeEnd).replace(/\s+$/, '').replace(/[,:]\s*$/, '');
  const keyAfterComma = /,\s*"(?:[^"\\]|\\.)*"$/.exec(prefix);
  if (keyAfterComma) {
    prefix = prefix.slice(0, prefix.length - keyAfterComma[0].length);
  } else {
    const keyAfterBrace = /\{\s*"(?:[^"\\]|\\.)*"$/.exec(prefix);
    if (keyAfterBrace) {
      prefix = prefix.slice(0, prefix.length - keyAfterBrace[0].length + 1);
    }
  }
  const stackForPrefix: string[] = [];
  let inS = false;
  let esc = false;
  for (let i = 0; i < prefix.length; i++) {
    const c = prefix[i];
    if (esc) {
      esc = false;
      continue;
    }
    if (inS) {
      if (c === '\\') {
        esc = true;
        continue;
      }
      if (c === '"') {
        inS = false;
      }
      continue;
    }
    if (c === '"') {
      inS = true;
      continue;
    }
    if (c === '{' || c === '[') {
      stackForPrefix.push(c);
    } else if (c === '}' || c === ']') {
      stackForPrefix.pop();
    }
  }
  let closing = '';
  while (stackForPrefix.length > 0) {
    const top = stackForPrefix.pop();
    closing += top === '{' ? '}' : ']';
  }
  return prefix + closing;
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
      try {
        return { value: JSON.parse(closeTruncatedJson(stripped)), repaired: true };
      } catch {
        return undefined;
      }
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
