import { useTracesExplorerContext } from '../../TracesExplorerContext';
import TraceFacetRailView from './TraceFacetRailView';

const TraceFacetRail = () => {
  const {
    facetsData,
    serviceForValue,
    facetSelections,
    search,
    onSearchChange,
    toggleFacet,
    clearFacet,
  } = useTracesExplorerContext();

  return (
    <TraceFacetRailView
      facets={facetsData}
      serviceForValue={serviceForValue}
      selections={facetSelections}
      search={search}
      onSearchChange={onSearchChange}
      onToggle={toggleFacet}
      onClear={clearFacet}
    />
  );
};

export default TraceFacetRail;
