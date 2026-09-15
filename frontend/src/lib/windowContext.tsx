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
  const [selection, setSelection] = useState<WindowSelection>(DEFAULT_SELECTION);
  const [autoRefresh, setAutoRefresh] = useState<boolean>(false);
  const [repositoryUrl, setRepositoryUrl] = useState<string | null>(null);

  const value = useMemo<WindowContextValue>(
    () => ({
      selection,
      setSelection,
      autoRefresh,
      setAutoRefresh,
      repositoryUrl,
      setRepositoryUrl,
    }),
    [selection, autoRefresh, repositoryUrl],
  );

  return (
    <WindowContext.Provider value={value}>{children}</WindowContext.Provider>
  );
};
