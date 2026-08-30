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
// Shared helpers for the page-local synthetic data stores
// (pages/LogsPage/logsSampleData.ts, pages/TracesPage/tracesSampleData.ts), which
// back the VITE_*_SAMPLE offline-UI fixtures.
//
// Each store calls createSampleRng(seed) to get its OWN deterministic sequence.
// The per-store seed keeps a page's mock data stable across reloads WITHOUT
// sharing RNG state between pages — a single shared module-level seed would make
// the two stores' outputs depend on each other's call order.

export interface SampleRng {
  /** next float in [0, 1) from the seeded LCG */
  rnd: () => number;
  /** uniformly pick one element of a non-empty array */
  pick: <T>(items: readonly T[]) => T;
  /** random integer in [lo, hi] inclusive */
  ri: (lo: number, hi: number) => number;
  /** lowercase hex string of the given length (synthetic trace/span/session ids) */
  hx: (length: number) => string;
}

const HEX = '0123456789abcdef';

export const createSampleRng = (seed: number): SampleRng => {
  let state = seed;
  const rnd = () => {
    state = (state * 1103515245 + 12345) & 0x7fffffff;
    return state / 0x7fffffff;
  };
  const pick = <T,>(items: readonly T[]): T => items[Math.floor(rnd() * items.length)];
  const ri = (lo: number, hi: number) => Math.floor(lo + rnd() * (hi - lo + 1));
  const hx = (length: number) => {
    let result = '';
    for (let i = 0; i < length; i += 1) {
      result += HEX[Math.floor(rnd() * 16)];
    }
    return result;
  };
  return { rnd, pick, ri, hx };
};

/** Resolve after `ms` — emulates network latency in the sample stores. */
export const latency = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, ms));
