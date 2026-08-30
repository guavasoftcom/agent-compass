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
import { Button, Paper, Typography } from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import { groupForPath } from '../../App/navGroups';
import type { WindowSelection } from '../../api';

export interface ReportPageViewProps {
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  data: string | undefined;
  isLoading: boolean;
  error: Error | null;
  copied: boolean;
  onCopy: () => void;
  onReload: () => void;
}

export default function ReportPageView({
  selection,
  onSelectionChange,
  data,
  isLoading,
  error,
  copied,
  onCopy,
  onReload,
}: ReportPageViewProps) {
  return (
    <PageLayout
      eyebrow={groupForPath('/report')}
      title="Tuning Report"
      subtitle="Paste the rendered summary back into your coding agent to tune its prompts and skills."
      error={error}
      actions={
        <PageActions
          selection={selection}
          onSelectionChange={onSelectionChange}
          onReload={onReload}
          hideAutoRefresh
          extraActions={
            <Button
              variant="contained"
              startIcon={<ContentCopyIcon />}
              onClick={onCopy}
              disabled={!data || isLoading}
              sx={{ whiteSpace: 'nowrap', flexShrink: 0 }}
            >
              {copied ? 'Copied' : 'Copy markdown'}
            </Button>
          }
        />
      }
    >
      <Paper
        variant="outlined"
        sx={{ p: 2, height: `calc(100vh - 180px)`, overflow: 'auto' }}
      >
        <Typography
          component="pre"
          sx={{
            typography: 'mono',
            m: 0,
            fontSize: 14,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {isLoading ? 'Loading…' : (data ?? '')}
        </Typography>
      </Paper>
    </PageLayout>
  );
}
