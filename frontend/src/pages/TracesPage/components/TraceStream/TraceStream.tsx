import { useTracesExplorerContext } from '../../TracesExplorerContext';
import TraceStreamView from './TraceStreamView';

const TraceStream = () => {
  const {
    streamRows,
    streamTotal,
    streamLoading,
    streamHasMore,
    expanded,
    toggleExpand,
    loadMore,
  } = useTracesExplorerContext();

  return (
    <TraceStreamView
      rows={streamRows}
      total={streamTotal}
      loading={streamLoading}
      hasMore={streamHasMore}
      expanded={expanded}
      onToggleExpand={toggleExpand}
      onLoadMore={loadMore}
    />
  );
};

export default TraceStream;
