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
import { describe, expect, it } from 'vitest';
import { SEARCH_DEBOUNCE_MS, useDebouncedValue } from './useDebouncedValue';

// `useDebouncedValue` is a React hook (useState + useEffect + setTimeout), so
// exercising its actual debounce behavior requires mounting it inside a
// component and letting React run its effects. This repo has no jsdom/
// happy-dom/testing-library/react-test-renderer (see
// src/components/ErrorBoundary/ErrorBoundary.test.tsx for the same constraint
// on class components, and note F1/F2's explicit "no new deps" requirement),
// and calling a hook outside of React's render cycle throws "Invalid hook
// call" — there's no dispatcher installed. So this only covers what's testable
// without a renderer: the shared delay constant the Logs/Traces search boxes
// are wired to. The wiring itself (search box stays immediate, `filters`
// receives the debounced copy) was verified by hand in the dev server per the
// task's UI-verification requirement.
describe('SEARCH_DEBOUNCE_MS', () => {
  it('is the shared 250ms delay used by the Logs and Traces search boxes', () => {
    expect(SEARCH_DEBOUNCE_MS).toBe(250);
  });
});

describe('useDebouncedValue', () => {
  it('is exported as a function taking a value and an optional delay', () => {
    expect(typeof useDebouncedValue).toBe('function');
    expect(useDebouncedValue.length).toBe(1); // `delayMs` has a default, so arity is 1
  });
});
