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
import { fetchProfiles } from '../../api';
import AgentProfileSelectorView from './AgentProfileSelectorView';

export interface AgentProfileSelectorProps {
  /** `null` = the server's primary profile. There is no "all agents" value. */
  value: string | null;
  onValueChange: (next: string | null) => void;
}

const AgentProfileSelector = ({ value, onValueChange }: AgentProfileSelectorProps) => {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  // Not window-scoped: lists every profile that has any telemetry at all.
  const profilesQuery = useQuery({
    queryKey: ['system-profiles'],
    queryFn: fetchProfiles,
  });

  return (
    <AgentProfileSelectorView
      value={value}
      primaryAgentProfile={profilesQuery.data?.primaryAgentProfile ?? null}
      profiles={profilesQuery.data?.profiles ?? []}
      isLoading={profilesQuery.isLoading}
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

export default AgentProfileSelector;
