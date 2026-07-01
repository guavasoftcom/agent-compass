import { Box, Paper } from '@mui/material';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import { useTracesExplorerContext } from './TracesExplorerContext';
import TraceHistogram from './components/TraceHistogram';
import TraceFacetRail from './components/TraceFacetRail';
import TraceStream from './components/TraceStream';
import TraceTable from './components/TraceTable';
import TraceViewToggle from './components/TraceViewToggle';
import TraceTailToggle from './components/TraceTailToggle';
import TraceSortDropdown from './components/TraceSortDropdown';
import TraceFilterChips from './components/TraceFilterChips';
import { fontFamilies } from '../../theme/typography';

const TracesPageView = () => {
  const {
    selection,
    onSelectionChange,
    windows,
    error,
    onReload,
    autoRefresh,
    onAutoRefreshChange,
    isPolling,
    view,
    onViewChange,
    sort,
    onSortChange,
    tail,
    tailLocked,
    tailTip,
    toggleTail,
    totalCount,
  } = useTracesExplorerContext();

  return (
    <PageLayout
      eyebrow="Observability"
      title="Traces"
      subtitle={
        'Every distributed trace captured across agent sessions. Scan the throughput timeline for ' +
        'error bursts, sort by latency to surface the slowest operations, then expand any trace to ' +
        'read its span waterfall inline.'
      }
      error={error}
      actions={
        <PageActions
          selection={selection}
          onSelectionChange={onSelectionChange}
          windows={windows}
          onReload={onReload}
          autoRefresh={autoRefresh}
          onAutoRefreshChange={onAutoRefreshChange}
          isPolling={isPolling}
          autoRefreshDisabled={tailLocked}
        />
      }
    >
      <Paper variant="outlined" sx={{ p: 2.25, mb: 2 }}>
        <TraceHistogram />
      </Paper>

      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 1.75,
          mb: 1.25,
          flexWrap: 'wrap',
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25 }}>
          <TraceViewToggle view={view} onViewChange={onViewChange} />
          <TraceTailToggle
            active={tail}
            locked={tailLocked}
            tooltip={tailTip}
            onToggle={toggleTail}
          />
        </Box>

        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.1 }}>
          <TraceSortDropdown sort={sort} onSortChange={onSortChange} />
          <Box
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 0.6,
              height: 40,
              px: 1.9,
              borderRadius: 1.5,
              border: 1,
              borderColor: 'divider',
              bgcolor: 'background.paper',
              boxShadow: 1,
              whiteSpace: 'nowrap',
              flexShrink: 0,
            }}
          >
            <Box
              component="b"
              sx={{
                color: 'text.primary',
                fontWeight: 700,
                fontFamily: fontFamilies.display,
                fontSize: 13,
              }}
            >
              {totalCount.toLocaleString()}
            </Box>
            <Box
              component="span"
              sx={{
                color: 'text.secondary',
                fontFamily: fontFamilies.display,
                fontWeight: 600,
                fontSize: 13,
              }}
            >
              traces
            </Box>
          </Box>
        </Box>
      </Box>

      <TraceFilterChips />

      {view === 'stream' ? (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: '236px 1fr' },
            gap: 2,
            height: 'calc(100vh - 480px)',
            minHeight: 400,
          }}
        >
          <Paper
            variant="outlined"
            sx={{
              overflow: 'hidden',
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            <TraceFacetRail />
          </Paper>
          <Paper
            variant="outlined"
            sx={{
              overflow: 'hidden',
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            <TraceStream />
          </Paper>
        </Box>
      ) : (
        <Paper
          variant="outlined"
          sx={{
            overflow: 'hidden',
            display: 'flex',
            flexDirection: 'column',
            height: 'calc(100vh - 480px)',
            minHeight: 400,
          }}
        >
          <TraceTable />
        </Paper>
      )}
    </PageLayout>
  );
};

export default TracesPageView;
