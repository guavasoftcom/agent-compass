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
import { useQuery } from '@tanstack/react-query';
import { fetchRepositories } from '../../api';
import RepositorySelectorView from './RepositorySelectorView';

export interface RepositorySelectorProps {
  /** `null` = all repositories (the default). */
  value: string | null;
  onValueChange: (next: string | null) => void;
}

const RepositorySelector = ({ value, onValueChange }: RepositorySelectorProps) => {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  // Not window-scoped: the picker lists every repository ever attributed,
  // regardless of the currently selected time range.
  const repositoriesQuery = useQuery({
    queryKey: ['system-repositories'],
    queryFn: fetchRepositories,
  });

  const repositories = repositoriesQuery.data ?? [];

  return (
    <RepositorySelectorView
      value={value}
      repositories={repositories}
      isLoading={repositoriesQuery.isLoading}
      anchor={anchor}
      onAnchorOpen={(event) => setAnchor(event.currentTarget)}
      onAnchorClose={() => setAnchor(null)}
      onSelect={(next) => {
        onValueChange(next);
        setAnchor(null);
      }}
    />
  );
};

export default RepositorySelector;
