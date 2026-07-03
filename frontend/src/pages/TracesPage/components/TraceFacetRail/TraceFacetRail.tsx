import { useMemo } from 'react';
import { useTheme } from '@mui/material';
import FacetRail, { type FacetRailSection } from '../../../../components/FacetRail';
import {
  DURATION_BUCKETS,
  type FacetKey,
} from '../../tracesApi';
import { serviceColor } from '../traceColors';
import { useTracesExplorerContext } from '../../TracesExplorerContext';

/**
 * The full set of facet selection state for the Traces explorer.
 * Owned here (the container) and consumed by useTracesExplorer via the barrel index.
 */
export interface TraceFacetSelections {
  status: Set<string>;
  operation: Set<string>;
  service: Set<string>;
  duration: Set<string>;
  session: Set<string>;
}

const DURATION_LABEL = new Map(DURATION_BUCKETS.map((bucket) => [bucket.id, bucket.label]));

/**
 * Context-reading container for the trace facet rail.
 *
 * This component reads all facet state from TracesExplorerContext, pre-computes
 * the per-section item lists (including dot colors, label maps, and monospace flags),
 * then delegates all rendering to the shared <FacetRail> component.
 *
 * Dot-color logic:
 *   - status: error → theme.palette.error.main; ok → theme.palette.primary.main
 *   - operation/service: service registry color via serviceColor()
 *   - duration/session: no dot
 *
 * Label mapping:
 *   - duration: DURATION_LABEL map (e.g. 'd0' → '< 100 ms')
 *   - all others: raw value
 *
 * Ordering: server-returned order (facetsData already comes sorted by count desc).
 */
const TraceFacetRail = () => {
  const theme = useTheme();
  const {
    facetsData,
    serviceForValue,
    facetSelections,
    search,
    onSearchChange,
    toggleFacet,
    clearFacet,
  } = useTracesExplorerContext();

  const sections: FacetRailSection<FacetKey>[] = useMemo(() => [
    {
      key: 'status',
      title: 'Status',
      items: (facetsData?.status ?? []).map((row) => ({
        value: row.value,
        label: row.value,
        count: row.count,
        selected: facetSelections.status.has(row.value),
        color: row.value === 'error'
          ? theme.palette.error.main
          : theme.palette.primary.main,
      })),
      onToggle: (value) => toggleFacet('status', value),
      onClear: () => clearFacet('status'),
    },
    {
      key: 'operation',
      title: 'Operation',
      items: (facetsData?.operation ?? []).map((row) => {
        const resolvedService = serviceForValue('operation', row.value);
        return {
          value: row.value,
          label: row.value,
          count: row.count,
          selected: facetSelections.operation.has(row.value),
          color: resolvedService ? serviceColor(resolvedService) : null,
          monospace: true,
        };
      }),
      onToggle: (value) => toggleFacet('operation', value),
      onClear: () => clearFacet('operation'),
    },
    {
      key: 'service',
      title: 'Service',
      items: (facetsData?.service ?? []).map((row) => {
        const resolvedService = serviceForValue('service', row.value);
        return {
          value: row.value,
          label: row.value,
          count: row.count,
          selected: facetSelections.service.has(row.value),
          color: resolvedService ? serviceColor(resolvedService) : null,
          monospace: true,
        };
      }),
      onToggle: (value) => toggleFacet('service', value),
      onClear: () => clearFacet('service'),
    },
    {
      key: 'duration',
      title: 'Duration',
      items: (facetsData?.duration ?? []).map((row) => ({
        value: row.value,
        label: DURATION_LABEL.get(row.value) ?? row.value,
        count: row.count,
        selected: facetSelections.duration.has(row.value),
      })),
      onToggle: (value) => toggleFacet('duration', value),
      onClear: () => clearFacet('duration'),
    },
    {
      key: 'session',
      title: 'Session',
      items: (facetsData?.session ?? []).map((row) => ({
        value: row.value,
        label: row.value,
        count: row.count,
        selected: facetSelections.session.has(row.value),
        monospace: true,
      })),
      onToggle: (value) => toggleFacet('session', value),
      onClear: () => clearFacet('session'),
    },
  ], [facetsData, facetSelections, theme, serviceForValue, toggleFacet, clearFacet]);

  return (
    <FacetRail
      sections={sections}
      search={search}
      onSearchChange={onSearchChange}
      searchPlaceholder="Search trace / session / op…"
    />
  );
};

export default TraceFacetRail;
