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
import { buildWindowSelectionKey } from './queryKeys';

describe('buildWindowSelectionKey', () => {
  it('folds the window, repository and agent profile into one key', () => {
    expect(
      buildWindowSelectionKey({ kind: 'preset', minutes: 60 }, 'https://github.com/a/b', 'unknown'),
    ).toBe('preset:60:repository:https://github.com/a/b:profile:unknown');
  });

  it('uses "all" for no repository and "primary" for no agent profile', () => {
    expect(buildWindowSelectionKey({ kind: 'preset', minutes: 1440 }, null, null)).toBe(
      'preset:1440:repository:all:profile:primary',
    );
  });

  it('keys a custom range by both endpoints', () => {
    expect(
      buildWindowSelectionKey(
        { kind: 'custom', startTimestamp: '2026-01-01T00:00:00Z', endTimestamp: '2026-01-02T00:00:00Z' },
        null,
        'claude-code',
      ),
    ).toBe('custom:2026-01-01T00:00:00Z:2026-01-02T00:00:00Z:repository:all:profile:claude-code');
  });

  it('produces distinct keys for different profiles so a switch cannot serve stale data', () => {
    const selection = { kind: 'preset', minutes: 60 } as const;
    expect(buildWindowSelectionKey(selection, null, 'claude-code')).not.toBe(
      buildWindowSelectionKey(selection, null, 'unknown'),
    );
  });
});
