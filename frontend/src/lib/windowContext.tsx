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
import {
  createContext,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import type { WindowSelection } from '../api';

export interface WindowContextValue {
  selection: WindowSelection;
  setSelection: (next: WindowSelection) => void;
  autoRefresh: boolean;
  setAutoRefresh: (next: boolean) => void;
  /**
   * Distinct repository (`vcs.repository.url.full`) every window-scoped page
   * should be filtered to, or `null` for "all repositories" (the default —
   * existing numbers must not silently change until a repo is picked). Set via
   * `RepositorySelector`, rendered beside `WindowSelector` in `PageActions`.
   * `null` and `UNATTRIBUTED_REPOSITORY` are the two special values; anything
   * else is a real repository URL from `GET /api/system/repositories`.
   */
  repositoryUrl: string | null;
  setRepositoryUrl: (next: string | null) => void;
}

const DEFAULT_SELECTION: WindowSelection = { kind: 'preset', minutes: 60 * 24 };

const SELECTION_STORAGE_KEY = 'ac-window-selection';
const AUTO_REFRESH_STORAGE_KEY = 'ac-window-auto-refresh';
const REPOSITORY_URL_STORAGE_KEY = 'ac-window-repository-url';

const isWindowSelection = (value: unknown): value is WindowSelection => {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  if (candidate.kind === 'preset') {
    return typeof candidate.minutes === 'number';
  }
  if (candidate.kind === 'custom') {
    return (
      typeof candidate.startTimestamp === 'string' &&
      typeof candidate.endTimestamp === 'string'
    );
  }
  return false;
};

const readInitialSelection = (): WindowSelection => {
  if (typeof window === 'undefined') {
    return DEFAULT_SELECTION;
  }
  try {
    const stored = window.localStorage?.getItem(SELECTION_STORAGE_KEY);
    const parsed: unknown = stored ? JSON.parse(stored) : null;
    return isWindowSelection(parsed) ? parsed : DEFAULT_SELECTION;
  } catch {
    return DEFAULT_SELECTION;
  }
};

const readInitialAutoRefresh = (): boolean => {
  if (typeof window === 'undefined') {
    return false;
  }
  return window.localStorage?.getItem(AUTO_REFRESH_STORAGE_KEY) === 'true';
};

const readInitialRepositoryUrl = (): string | null => {
  if (typeof window === 'undefined') {
    return null;
  }
  return window.localStorage?.getItem(REPOSITORY_URL_STORAGE_KEY) ?? null;
};

const persistSelection = (selection: WindowSelection): void => {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage?.setItem(SELECTION_STORAGE_KEY, JSON.stringify(selection));
  } catch {
    // ignore quota / disabled storage — the selection still works for the
    // session, it just won't survive a reload
  }
};

const persistAutoRefresh = (autoRefresh: boolean): void => {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage?.setItem(AUTO_REFRESH_STORAGE_KEY, String(autoRefresh));
  } catch {
    // ignore quota / disabled storage
  }
};

const persistRepositoryUrl = (repositoryUrl: string | null): void => {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    if (repositoryUrl === null) {
      window.localStorage?.removeItem(REPOSITORY_URL_STORAGE_KEY);
    } else {
      window.localStorage?.setItem(REPOSITORY_URL_STORAGE_KEY, repositoryUrl);
    }
  } catch {
    // ignore quota / disabled storage
  }
};

const WindowContext = createContext<WindowContextValue>({
  selection: DEFAULT_SELECTION,
  setSelection: () => {},
  autoRefresh: false,
  setAutoRefresh: () => {},
  repositoryUrl: null,
  setRepositoryUrl: () => {},
});

export const useWindowContext = (): WindowContextValue =>
  useContext(WindowContext);

export interface WindowProviderProps {
  children: ReactNode;
}

export const WindowProvider = ({ children }: WindowProviderProps) => {
  const [selection, setSelectionState] = useState<WindowSelection>(() => readInitialSelection());
  const [autoRefresh, setAutoRefreshState] = useState<boolean>(() => readInitialAutoRefresh());
  const [repositoryUrl, setRepositoryUrlState] = useState<string | null>(() => readInitialRepositoryUrl());

  const value = useMemo<WindowContextValue>(
    () => ({
      selection,
      setSelection: (next: WindowSelection) => {
        persistSelection(next);
        setSelectionState(next);
      },
      autoRefresh,
      setAutoRefresh: (next: boolean) => {
        persistAutoRefresh(next);
        setAutoRefreshState(next);
      },
      repositoryUrl,
      setRepositoryUrl: (next: string | null) => {
        persistRepositoryUrl(next);
        setRepositoryUrlState(next);
      },
    }),
    [selection, autoRefresh, repositoryUrl],
  );

  return (
    <WindowContext.Provider value={value}>{children}</WindowContext.Provider>
  );
};
