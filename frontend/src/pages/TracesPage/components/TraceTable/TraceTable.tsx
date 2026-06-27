import { useTracesExplorerContext } from '../../TracesExplorerContext';
import TraceTableView from './TraceTableView';

const TraceTable = () => {
  const {
    tableRows,
    tableTotal,
    page,
    pageSize,
    tableLoading,
    expanded,
    toggleExpand,
    onTablePageChange,
    onTablePageSizeChange,
  } = useTracesExplorerContext();

  return (
    <TraceTableView
      rows={tableRows}
      total={tableTotal}
      page={page}
      pageSize={pageSize}
      loading={tableLoading}
      expanded={expanded}
      onToggleExpand={toggleExpand}
      onPageChange={onTablePageChange}
      onPageSizeChange={onTablePageSizeChange}
    />
  );
};

export default TraceTable;
