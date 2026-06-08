import { useMemo } from 'react';
import { Box, Button, Chip, Paper } from '@mui/material';
import { DataGrid, type GridColDef } from '@mui/x-data-grid';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import { AttributeList } from '../../components/AttributeList';
import type { LogRow, WindowSelection } from '../../api';
import LogsFilterBar from './LogsFilterBar';

const BODY_PREVIEW_LENGTH = 140;

export interface PaginationModel {
  pageSize: number;
  page: number;
}

export interface LogsPageViewProps {
  rows: LogRow[];
  isLoading: boolean;
  error: Error | null;
  paginationModel: PaginationModel;
  onPaginationModelChange: (model: PaginationModel) => void;
  selectedFilters: string[];
  onSelectedFiltersChange: (filters: string[]) => void;
  autocompleteOptions: string[];
  autocompleteInput: string;
  onAutocompleteInputChange: (next: string) => void;
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  onReload: () => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (next: boolean) => void;
  isPolling: boolean;
  onOpenBody: (body: string) => void;
}

export default function LogsPageView({
  rows,
  isLoading,
  error,
  paginationModel,
  onPaginationModelChange,
  selectedFilters,
  onSelectedFiltersChange,
  autocompleteOptions,
  autocompleteInput,
  onAutocompleteInputChange,
  selection,
  onSelectionChange,
  onReload,
  autoRefresh,
  onAutoRefreshChange,
  isPolling,
  onOpenBody,
}: LogsPageViewProps) {
  const columns = useMemo<GridColDef<LogRow>[]>(
    () => [
      { field: 'id', headerName: 'ID', width: 80 },
      {
        field: 'timestamp',
        headerName: 'Timestamp',
        width: 200,
        valueFormatter: (value) =>
          value ? new Date(value as string).toLocaleString() : '',
      },
      {
        field: 'severityText',
        headerName: 'Severity',
        width: 120,
        sortable: false,
        renderCell: (params) => {
          const { severityText, severityNumber } = params.row as LogRow;
          if (!severityText && severityNumber == null) {
            return null;
          }
          const number = severityNumber ?? -1;
          const chipColor: 'error' | 'warning' | 'info' | 'default' =
            number >= 17 ? 'error' : number >= 13 ? 'warning' : number >= 9 ? 'info' : 'default';
          return (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
              {severityText && (
                <Chip
                  label={severityText}
                  color={chipColor}
                  size="small"
                  sx={{ fontSize: '0.6875rem', height: 20 }}
                />
              )}
              {severityNumber != null && (
                <Box
                  component="span"
                  sx={{ fontSize: '0.6875rem', color: 'text.secondary' }}
                >
                  {severityNumber}
                </Box>
              )}
            </Box>
          );
        },
      },
      {
        field: 'body',
        headerName: 'Body',
        flex: 1,
        minWidth: 240,
        sortable: false,
        renderCell: (params) => {
          const body = params.value as string | null | undefined;
          if (!body) {
            return null;
          }
          const isLong = body.length > BODY_PREVIEW_LENGTH;
          const preview = isLong
            ? `${body.slice(0, BODY_PREVIEW_LENGTH)}…`
            : body;
          return (
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: 0.5,
                width: '100%',
                fontFamily: 'monospace',
                fontSize: '0.75rem',
              }}
            >
              <Box
                sx={{
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  flex: 1,
                  minWidth: 0,
                }}
                title={isLong ? undefined : body}
              >
                {preview.replace(/\s+/g, ' ')}
              </Box>
              {isLong && (
                <Button
                  size="small"
                  variant="text"
                  onClick={() => onOpenBody(body)}
                  sx={{ flexShrink: 0, minWidth: 0, px: 0.5 }}
                >
                  View more
                </Button>
              )}
            </Box>
          );
        },
      },
      { field: 'scopeName', headerName: 'Scope', width: 180 },
      {
        field: 'attributes',
        headerName: 'Attributes',
        flex: 1.5,
        minWidth: 260,
        sortable: false,
        renderCell: (params) => {
          const attributes = params.value as
            | Record<string, unknown>
            | null
            | undefined;
          if (!attributes || Object.keys(attributes).length === 0) {
            return null;
          }
          return <AttributeList attributes={attributes} disableBackground />;
        },
      },
    ],
    [onOpenBody],
  );

  return (
    <PageLayout
      title="Logs"
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
      <LogsFilterBar
        selectedFilters={selectedFilters}
        onSelectedFiltersChange={onSelectedFiltersChange}
        autocompleteOptions={autocompleteOptions}
        autocompleteInput={autocompleteInput}
        onAutocompleteInputChange={onAutocompleteInputChange}
      />
      <Paper
        variant="outlined"
        sx={{ height: 'calc(100vh - 240px)', minHeight: 480 }}
      >
        <DataGrid
          rows={rows}
          columns={columns}
          loading={isLoading}
          density="compact"
          disableRowSelectionOnClick
          getRowHeight={(params) => {
            const attributes = (params.model as LogRow | undefined)?.attributes;
            if (!attributes || Object.keys(attributes).length === 0) {
              return null;
            }
            return 'auto';
          }}
          paginationModel={paginationModel}
          onPaginationModelChange={onPaginationModelChange}
          pageSizeOptions={[25, 50, 100]}
        />
      </Paper>

    </PageLayout>
  );
}
