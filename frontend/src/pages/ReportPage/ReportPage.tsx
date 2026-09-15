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
import { fetchReportMarkdown } from '../../api';
import { buildWindowSelectionKey } from '../../lib/queryKeys';
import { useWindowContext } from '../../lib/windowContext';
import ReportPageView from './ReportPageView';

const COPIED_TIMEOUT_MS = 1500;

export default function ReportPage() {
  const { selection, setSelection, repositoryUrl, setRepositoryUrl } = useWindowContext();
  const [copied, setCopied] = useState<boolean>(false);

  const selectionKey = buildWindowSelectionKey(selection, repositoryUrl);

  const reportQuery = useQuery({
    queryKey: ['report', selectionKey],
    queryFn: () => fetchReportMarkdown({ ...selection, repositoryUrl }),
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
      repositoryUrl={repositoryUrl}
      onRepositoryUrlChange={setRepositoryUrl}
      data={data}
      isLoading={isLoading}
      error={error as Error | null}
      copied={copied}
      onCopy={handleCopy}
      onReload={() => reportQuery.refetch()}
    />
  );
}
