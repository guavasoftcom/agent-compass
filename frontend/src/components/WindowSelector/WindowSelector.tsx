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

const formatRangeBoundary = (isoString: string): string => {
  const date = new Date(isoString);
  return date.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
};

// `<input type="datetime-local">` consumes/emits "YYYY-MM-DDTHH:mm" in local time, but the
// API speaks UTC ISO-8601. These two helpers bridge the gap so the user types in their
// local clock while we store / send absolute timestamps.
const isoToLocalInput = (isoString: string): string => {
  const date = new Date(isoString);
  const offsetMs = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offsetMs).toISOString().slice(0, 16);
};

const localInputToIso = (localValue: string): string | null => {
  if (!localValue) {
    return null;
  }
  const parsed = new Date(localValue);
  if (Number.isNaN(parsed.getTime())) {
    return null;
  }
  return parsed.toISOString();
};

const WindowSelector = ({
  selection,
  onSelectionChange,
  windows = WINDOWS,
}: WindowSelectorProps) => {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  // Local-time strings the datetime-local inputs bind to. Seeded once from the active
  // custom range, or a sensible default (last 24h) when the user is currently on a preset.
  // Lazy useState init keeps the impure `new Date()` out of the render path.
  const [startInput, setStartInput] = useState<string>(() => {
    if (selection.kind === 'custom') {
      return isoToLocalInput(selection.startTimestamp);
    }
    return isoToLocalInput(
      new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
    );
  });
  const [endInput, setEndInput] = useState<string>(() => {
    if (selection.kind === 'custom') {
      return isoToLocalInput(selection.endTimestamp);
    }
    return isoToLocalInput(new Date().toISOString());
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
    const startIso = localInputToIso(startInput);
    const endIso = localInputToIso(endInput);
    if (!startIso || !endIso) {
      return;
    }
    onSelectionChange({
      kind: 'custom',
      startTimestamp: startIso,
      endTimestamp: endIso,
    });
    setAnchor(null);
  };

  const rangeTooLarge =
    !!startInput &&
    !!endInput &&
    new Date(endInput).getTime() - new Date(startInput).getTime() > MAX_WINDOW_SPAN_MS;

  const customApplyDisabled =
    !startInput ||
    !endInput ||
    new Date(startInput).getTime() >= new Date(endInput).getTime() ||
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
