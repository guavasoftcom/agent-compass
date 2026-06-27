import type { FacetKey } from '../../tracesApi';
import { useTracesExplorerContext } from '../../TracesExplorerContext';
import TraceFilterChipsView, { type TraceFilterChip } from './TraceFilterChipsView';

const FACET_KEYS: FacetKey[] = ['status', 'operation', 'service', 'duration', 'session'];

const DURATION_CHIP_LABELS = new Map([
  ['d0', '< 100 ms'],
  ['d1', '100 ms – 1 s'],
  ['d2', '1 s – 5 s'],
  ['d3', '> 5 s'],
]);

const TraceFilterChips = () => {
  const {
    zoom,
    search,
    facetSelections,
    toggleFacet,
    onSearchChange,
    clearAllFilters,
    clearZoom,
  } = useTracesExplorerContext();
  const zoomLabel = zoom ? zoom.label : null;

  const chips: TraceFilterChip[] = [];
  if (search) {
    chips.push({ key: 'q', value: search, label: `"${search}"` });
  }
  FACET_KEYS.forEach((key) => {
    facetSelections[key].forEach((value) => {
      chips.push({ key, value, label: key === 'duration' ? DURATION_CHIP_LABELS.get(value) ?? value : value });
    });
  });

  if (!zoomLabel && chips.length === 0) {
    return null;
  }

  const removeChip = (chip: TraceFilterChip) => {
    if (chip.key === 'q') {
      onSearchChange('');
    } else {
      toggleFacet(chip.key, chip.value);
    }
  };

  return (
    <TraceFilterChipsView
      zoomLabel={zoomLabel}
      chips={chips}
      onRemoveChip={removeChip}
      onClearAll={clearAllFilters}
      onClearZoom={clearZoom}
    />
  );
};

export default TraceFilterChips;
