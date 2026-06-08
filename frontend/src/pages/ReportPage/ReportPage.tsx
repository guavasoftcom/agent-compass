import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchReportMarkdown } from '../../api';
import { useWindowContext } from '../../windowContext';
import ReportPageView from './ReportPageView';

const COPIED_TIMEOUT_MS = 1500;

export default function ReportPage() {
  const { selection, setSelection } = useWindowContext();
  const [copied, setCopied] = useState<boolean>(false);

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const reportQuery = useQuery({
    queryKey: ['report', selectionKey],
    queryFn: () => fetchReportMarkdown(selection),
  });
  const { data, isLoading, error } = reportQuery;

  const handleCopy = async () => {
    if (!data) {
      return;
    }
    await navigator.clipboard.writeText(data);
    setCopied(true);
    setTimeout(() => setCopied(false), COPIED_TIMEOUT_MS);
  };

  return (
    <ReportPageView
      selection={selection}
      onSelectionChange={setSelection}
      data={data}
      isLoading={isLoading}
      error={error as Error | null}
      copied={copied}
      onCopy={handleCopy}
      onReload={() => reportQuery.refetch()}
    />
  );
}
