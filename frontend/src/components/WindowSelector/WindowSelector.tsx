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
import { useState } from 'react';
import type { WindowSelection } from '../../api';
import { MAX_WINDOW_SPAN_MS, WINDOWS, type WindowOption } from '../../lib/constants';
import WindowSelectorView from './WindowSelectorView';

export interface WindowSelectorProps {
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  windows?: readonly WindowOption[];
}

const pad = (n: number): string => String(n).padStart(2, '0');

const formatRangeBoundary = (isoString: string): string => {
  const date = new Date(isoString);
  return date.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
  });
};

// The calendar's day cells emit/consume plain "YYYY-MM-DD" local-date strings — no
// time-of-day input, so every custom range is a whole number of calendar days. These
// helpers bridge that to the API's UTC ISO-8601 instants: a picked day always expands
// to 00:00:00.000 (start) or 23:59:59.999 (end) of that day in the browser's local
// timezone, since this app runs on the operator's own workstation.
const isoToLocalDateInput = (isoString: string): string => {
  const date = new Date(isoString);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
};

const parseLocalDateInput = (localValue: string): Date | null => {
  if (!localValue) {
    return null;
  }
  const [year, month, day] = localValue.split('-').map(Number);
  if (!year || !month || !day) {
    return null;
  }
  const date = new Date(year, month - 1, day);
  return Number.isNaN(date.getTime()) ? null : date;
};

const startOfDayIso = (date: Date): string =>
  new Date(date.getFullYear(), date.getMonth(), date.getDate(), 0, 0, 0, 0).toISOString();

const endOfDayIso = (date: Date): string =>
  new Date(date.getFullYear(), date.getMonth(), date.getDate(), 23, 59, 59, 999).toISOString();

const WindowSelector = ({
  selection,
  onSelectionChange,
  windows = WINDOWS,
}: WindowSelectorProps) => {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  // "YYYY-MM-DD" local-date strings the calendar's day cells bind to. Seeded once from
  // the active custom range's start/end days, or today when the user is currently on a
  // preset. Lazy useState init keeps the impure `new Date()` out of the render path.
  const [startInput, setStartInput] = useState<string>(() => {
    if (selection.kind === 'custom') {
      return isoToLocalDateInput(selection.startTimestamp);
    }
    return isoToLocalDateInput(new Date().toISOString());
  });
  const [endInput, setEndInput] = useState<string>(() => {
    if (selection.kind === 'custom') {
      return isoToLocalDateInput(selection.endTimestamp);
    }
    return isoToLocalDateInput(new Date().toISOString());
  });

  const isCustomActive = selection.kind === 'custom';

  const customSummary = isCustomActive
    ? `${formatRangeBoundary(selection.startTimestamp)} → ${formatRangeBoundary(selection.endTimestamp)}`
    : null;

  const buttonLabel =
    selection.kind === 'preset'
      ? `Last ${windows.find((window) => window.value === selection.minutes)?.label ?? 'window'}`
      : (customSummary ?? 'Custom range');

  const handleApplyCustomRange = () => {
    const startDate = parseLocalDateInput(startInput);
    const endDate = parseLocalDateInput(endInput);
    if (!startDate || !endDate) {
      return;
    }
    onSelectionChange({
      kind: 'custom',
      startTimestamp: startOfDayIso(startDate),
      endTimestamp: endOfDayIso(endDate),
    });
    setAnchor(null);
  };

  const startDate = parseLocalDateInput(startInput);
  const endDate = parseLocalDateInput(endInput);

  const rangeTooLarge =
    !!startDate &&
    !!endDate &&
    new Date(endOfDayIso(endDate)).getTime() - new Date(startOfDayIso(startDate)).getTime() > MAX_WINDOW_SPAN_MS;

  const customApplyDisabled =
    !startDate ||
    !endDate ||
    startDate.getTime() > endDate.getTime() ||
    rangeTooLarge;

  return (
    <WindowSelectorView
      windows={windows}
      buttonLabel={buttonLabel}
      isCustomActive={isCustomActive}
      customSummary={customSummary}
      anchor={anchor}
      onAnchorOpen={(event) => setAnchor(event.currentTarget)}
      onAnchorClose={() => setAnchor(null)}
      startInput={startInput}
      endInput={endInput}
      onStartInputChange={setStartInput}
      onEndInputChange={setEndInput}
      customApplyDisabled={customApplyDisabled}
      rangeTooLarge={rangeTooLarge}
      onApplyCustomRange={handleApplyCustomRange}
      isPresetSelected={(minutes) =>
        selection.kind === 'preset' && selection.minutes === minutes
      }
      onPresetSelect={(minutes) => {
        onSelectionChange({ kind: 'preset', minutes });
        setAnchor(null);
      }}
    />
  );
};

export default WindowSelector;
