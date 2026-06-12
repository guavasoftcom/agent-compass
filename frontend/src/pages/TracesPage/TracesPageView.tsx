import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Chip, Paper, Typography } from '@mui/material';
import { DataGrid, type GridColDef } from '@mui/x-data-grid';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import type { TraceRow, WindowSelection } from '../../api';
import { PAGE_SIZES } from '../../constants';
import { formatDuration } from './timeFormat';

export interface PaginationModel {
  pageSize: number;
  page: number;
}

export type TraceGridRow = TraceRow & { id: string };

export interface TracesPageViewProps {
  rows: TraceGridRow[];
  isLoading: boolean;
  error: Error | null;
  paginationModel: PaginationModel;
  onPaginationModelChange: (model: PaginationModel) => void;
  onReload: () => void;
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (next: boolean) => void;
  isPolling: boolean;
}

export default function TracesPageView({
  rows,
  isLoading,
  error,
  paginationModel,
  onPaginationModelChange,
  onReload,
  selection,
  onSelectionChange,
  autoRefresh,
  onAutoRefreshChange,
  isPolling,
}: TracesPageViewProps) {
  const navigate = useNavigate();

  const columns = useMemo<GridColDef<TraceGridRow>[]>(() => [
    {
      field: 'startTimestamp',
      headerName: 'Start',
      flex: 2,
      minWidth: 150,
      valueFormatter: (value) => (value ? new Date(value as string).toLocaleString() : ''),
    },
    {
      field: 'durationNanos',
      headerName: 'Duration',
      flex: 1,
      minWidth: 90,
      type: 'number',
      valueFormatter: (value) => formatDuration(value as number),
    },
    {
      field: 'errorCount',
      headerName: 'Errors',
      flex: 1,
      minWidth: 80,
      renderCell: (params) => {
        const count = params.value as number | undefined;
        if (!count || count === 0) {
          return <Typography variant="body2" color="text.disabled">—</Typography>;
        }
        return <Chip label={count} size="small" color="error" variant="outlined" />;
      },
    },
    { field: 'rootSpanName', headerName: 'Root span', flex: 2, minWidth: 160 },
    {
      field: 'spanCount',
      headerName: 'Spans',
      flex: 0.75,
      minWidth: 70,
      type: 'number',
    },
    {
      field: 'sessionId',
      headerName: 'Session',
      flex: 2.5,
      minWidth: 200,
      sortable: false,
      renderCell: (params) => (
        <Typography
          variant="body2"
          sx={{ display: 'flex', alignItems: 'center', height: '100%' }}
        >
          {(params.value as string) ?? '—'}
        </Typography>
      ),
    },
    {
      field: 'rootSpanId',
      headerName: 'Root span ID',
      flex: 1.5,
      minWidth: 140,
      sortable: false,
      renderCell: (params) => {
        const spanId = params.value as string | null;
        if (!spanId) {
          return <Typography variant="body2" color="text.disabled">—</Typography>;
        }
        return (
          <Typography
            variant="body2"
            sx={{ fontFamily: 'monospace', fontSize: '0.8rem', display: 'flex', alignItems: 'center', height: '100%' }}
          >
            {spanId}
          </Typography>
        );
      },
    },
    {
      field: 'traceId',
      headerName: 'Trace ID',
      flex: 2.5,
      minWidth: 200,
      sortable: false,
      renderCell: (params) => (
        <Typography
          variant="body2"
          sx={{ display: 'flex', alignItems: 'center', height: '100%' }}
        >
          {params.value as string}
        </Typography>
      ),
    },
  ], []);

  return (
    <PageLayout
      title="Traces"
      error={error}
      actions={
        <PageActions
          selection={selection}
          onSelectionChange={onSelectionChange}
          onReload={onReload}
          autoRefresh={autoRefresh}
          onAutoRefreshChange={onAutoRefreshChange}
          isPolling={isPolling}
        />
      }
    >
      <Paper variant="outlined" sx={{ height: 'calc(100vh - 180px)', minHeight: 480 }}>
        <DataGrid
          rows={rows}
          columns={columns}
          loading={isLoading}
          density="compact"
          disableRowSelectionOnClick
          onRowClick={(params) => navigate(`/traces/${params.row.traceId}`)}
          sx={{ '& .MuiDataGrid-row': { cursor: 'pointer' } }}
          paginationModel={paginationModel}
          onPaginationModelChange={onPaginationModelChange}
          pageSizeOptions={PAGE_SIZES}
        />
      </Paper>
    </PageLayout>
  );
}
