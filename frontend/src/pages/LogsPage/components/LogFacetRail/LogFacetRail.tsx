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
import { useTheme } from '@mui/material';
import FacetRail, { type FacetRailSection } from '../../../../components/FacetRail';
import { SEVERITIES, type FacetKey, type LogFacets, type Severity } from '../../logsApi';
import { severityColor } from '../severity';

export interface FacetSelections {
  severity: Set<string>;
  event: Set<string>;
  tool: Set<string>;
}

interface Props {
  facets: LogFacets | undefined;
  selections: FacetSelections;
  search: string;
  onSearchChange: (value: string) => void;
  onToggle: (key: FacetKey, value: string) => void;
  onClear: (key: FacetKey) => void;
}

/**
 * Builds the FacetRail sections config for the Logs explorer.
 *
 * Severity items are ordered by the canonical SEVERITIES array (ERROR → WARN → INFO → DEBUG).
 * Severity rows get a color dot from the theme palette; event and tool rows have no dot.
 * None of the facets use monospace labels.
 */
const LogFacetRail = ({
  facets,
  selections,
  search,
  onSearchChange,
  onToggle,
  onClear,
}: Props) => {
  const theme = useTheme();

  const sections: FacetRailSection<FacetKey>[] = [
    {
      key: 'severity',
      title: 'Severity',
      items: SEVERITIES
        .map((severityValue) => {
          const row = (facets?.severity ?? []).find((r) => r.value === severityValue);
          if (!row) {
            return null;
          }
          return {
            value: row.value,
            label: row.value,
            count: row.count,
            selected: selections.severity.has(row.value),
            color: severityColor(theme, row.value as Severity),
          };
        })
        .filter((item): item is NonNullable<typeof item> => item !== null),
      onToggle: (value) => onToggle('severity', value),
      onClear: () => onClear('severity'),
    },
    {
      key: 'event',
      title: 'Event type',
      items: (facets?.event ?? []).map((row) => ({
        value: row.value,
        label: row.value,
        count: row.count,
        selected: selections.event.has(row.value),
      })),
      onToggle: (value) => onToggle('event', value),
      onClear: () => onClear('event'),
    },
    {
      key: 'tool',
      title: 'Tool / server',
      items: (facets?.tool ?? []).map((row) => ({
        value: row.value,
        label: row.value,
        count: row.count,
        selected: selections.tool.has(row.value),
      })),
      onToggle: (value) => onToggle('tool', value),
      onClear: () => onClear('tool'),
    },
  ];

  return (
    <FacetRail
      sections={sections}
      search={search}
      onSearchChange={onSearchChange}
      searchPlaceholder="Search body & attributes…"
    />
  );
};

export default LogFacetRail;
