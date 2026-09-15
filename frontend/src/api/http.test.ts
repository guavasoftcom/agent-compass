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
import { windowQueryParams } from './http';
import { UNATTRIBUTED_REPOSITORY, type WindowSelection } from './types';

describe('windowQueryParams', () => {
  it('flattens a preset selection to a minutes param', () => {
    const selection: WindowSelection = { kind: 'preset', minutes: 1440 };
    expect(windowQueryParams(selection).toString()).toBe('minutes=1440');
  });

  it('flattens a custom selection to start/end timestamp params', () => {
    const selection: WindowSelection = {
      kind: 'custom',
      startTimestamp: '2026-09-01T00:00:00.000Z',
      endTimestamp: '2026-09-02T23:59:59.999Z',
    };
    expect(windowQueryParams(selection).toString()).toBe(
      'startTimestamp=2026-09-01T00%3A00%3A00.000Z&endTimestamp=2026-09-02T23%3A59%3A59.999Z',
    );
  });

  it('omits repositoryUrl entirely when null or absent — "all repositories" stays the default', () => {
    const selection: WindowSelection = { kind: 'preset', minutes: 1440 };
    expect(windowQueryParams(selection).has('repositoryUrl')).toBe(false);
    expect(windowQueryParams({ ...selection, repositoryUrl: null }).has('repositoryUrl')).toBe(false);
  });

  it('includes a real repository URL', () => {
    const selection: WindowSelection = {
      kind: 'preset',
      minutes: 1440,
      repositoryUrl: 'https://github.com/guavasoftcom/coding-agent-tuning',
    };
    expect(windowQueryParams(selection).get('repositoryUrl')).toBe(
      'https://github.com/guavasoftcom/coding-agent-tuning',
    );
  });

  it('includes the reserved unattributed sentinel', () => {
    const selection: WindowSelection = {
      kind: 'preset',
      minutes: 1440,
      repositoryUrl: UNATTRIBUTED_REPOSITORY,
    };
    expect(windowQueryParams(selection).get('repositoryUrl')).toBe(UNATTRIBUTED_REPOSITORY);
  });
});
