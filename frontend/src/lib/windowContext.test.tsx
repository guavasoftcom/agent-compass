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
import { beforeEach, describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { useWindowContext, WindowProvider } from './windowContext';

const wrapper = ({ children }: { children: ReactNode }) => <WindowProvider>{children}</WindowProvider>;

describe('WindowProvider agentProfile', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('defaults to null (the primary profile)', () => {
    const { result } = renderHook(() => useWindowContext(), { wrapper });
    expect(result.current.agentProfile).toBeNull();
  });

  it('persists a chosen profile and restores it on the next mount', () => {
    const first = renderHook(() => useWindowContext(), { wrapper });
    act(() => first.result.current.setAgentProfile('unknown'));
    expect(first.result.current.agentProfile).toBe('unknown');
    first.unmount();

    const second = renderHook(() => useWindowContext(), { wrapper });
    expect(second.result.current.agentProfile).toBe('unknown');
  });

  it('clears the stored value when reset to null', () => {
    const { result } = renderHook(() => useWindowContext(), { wrapper });
    act(() => result.current.setAgentProfile('unknown'));
    act(() => result.current.setAgentProfile(null));
    expect(window.localStorage.getItem('ac-window-agent-profile')).toBeNull();
  });
});
