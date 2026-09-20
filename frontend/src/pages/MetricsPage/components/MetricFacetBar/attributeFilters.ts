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
// Pure list operations for the filter bar's ANDed attribute filters (no React), split out so the
// add / replace / remove rules can be unit-tested without rendering the picker.
//
// The list holds at most one filter per key: two different values of one key ANDed together
// would match nothing, so choosing a value for a key that already has a chip REPLACES that chip
// (in place, keeping its position) instead of adding a second one.

import type { AttributeFilter } from '../../metricsApi';

/** True when this exact key = value pair is already an active filter. */
export const isFilterActive = (
  filters: readonly AttributeFilter[],
  key: string,
  value: string,
): boolean => filters.some((filter) => filter.key === key && filter.value === value);

/** The active filter on `key`, if any. */
export const findFilterForKey = (
  filters: readonly AttributeFilter[],
  key: string,
): AttributeFilter | undefined => filters.find((filter) => filter.key === key);

/**
 * The list after choosing `selected`: replaces the existing chip for the same key in place, or
 * appends a new chip. Choosing an already-active pair returns the list unchanged.
 */
export const applyFilterSelection = (
  filters: readonly AttributeFilter[],
  selected: AttributeFilter,
): AttributeFilter[] => {
  if (isFilterActive(filters, selected.key, selected.value)) {
    return [...filters];
  }
  if (findFilterForKey(filters, selected.key)) {
    return filters.map((filter) => (filter.key === selected.key ? selected : filter));
  }
  return [...filters, selected];
};

/** The list without the chip at `index` (out-of-range indexes leave it unchanged). */
export const removeFilterAt = (
  filters: readonly AttributeFilter[],
  index: number,
): AttributeFilter[] => filters.filter((_, filterIndex) => filterIndex !== index);
