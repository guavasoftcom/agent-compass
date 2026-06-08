import { useEffect, useRef, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import {
  Box,
  Button,
  Card,
  Chip,
  CircularProgress,
  Divider,
  Drawer,
  IconButton,
  Link,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import { alpha, styled } from '@mui/material/styles';
import type { SvgIconProps } from '@mui/material';
import { SimpleTreeView } from '@mui/x-tree-view/SimpleTreeView';
import { TreeItem, treeItemClasses } from '@mui/x-tree-view/TreeItem';
import ArticleIcon from '@mui/icons-material/Article';
import CloseIcon from '@mui/icons-material/Close';
import UnfoldLessIcon from '@mui/icons-material/UnfoldLess';
import UnfoldMoreIcon from '@mui/icons-material/UnfoldMore';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import FiberManualRecordIcon from '@mui/icons-material/FiberManualRecord';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import type { LogRow, SpanRow } from '../../api';
import { AttributeList } from '../../components/AttributeList';
import PageLayout from '../../components/PageLayout';
import { formatDuration } from '../TracesPage/timeFormat';
import {
  eventOffsetNanos,
  NANOS_PER_MILLI,
  severityColor,
  severityLabel,
  tokenBreakdownForSpan,
  type SpanTree,
  type TraceWindow,
} from './traceDetailHelpers';

const SPAN_ROW_HEIGHT_PX = 28;
const MIN_BAR_WIDTH_PCT = 0.4;
const NAME_COLUMN_WIDTH = '36%';

const CustomTreeItem = styled(TreeItem)(({ theme }) => ({
  [`& .${treeItemClasses.content}`]: {
    padding: theme.spacing(0, 0.5),
    borderBottom: `1px solid ${theme.palette.divider}`,
    borderRadius: 0,
    alignItems: 'flex-start',
  },
  [`& .${treeItemClasses.label}`]: {
    width: '100%',
  },
  [`& .${treeItemClasses.iconContainer}`]: {
    width: 22,
    height: SPAN_ROW_HEIGHT_PX,
    marginRight: theme.spacing(0.25),
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  [`& .${treeItemClasses.groupTransition}`]: {
    marginLeft: 15,
    paddingLeft: 18,
    borderLeft: `1px dashed ${alpha(theme.palette.text.primary, 0.4)}`,
  },
}));

const ExpandIcon = (props: SvgIconProps) => {
  return <PlayArrowIcon {...props} sx={{ fontSize: 12, opacity: 0.7 }} />;
};

const CollapseIcon = (props: SvgIconProps) => {
  return (
    <PlayArrowIcon
      {...props}
      sx={{ fontSize: 12, opacity: 0.7, transform: 'rotate(90deg)' }}
    />
  );
};

const EndIcon = (props: SvgIconProps) => {
  return (
    <FiberManualRecordIcon {...props} sx={{ fontSize: 6, opacity: 0.3 }} />
  );
};

const formatWallClock = (iso: string): string => {
  const date = new Date(iso);
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(date.getMinutes()).padStart(2, '0');
  const seconds = String(date.getSeconds()).padStart(2, '0');
  const millis = String(date.getMilliseconds()).padStart(3, '0');
  return `${hours}:${minutes}:${seconds}.${millis}`;
};

const LogDrawerRow = ({
  log,
  isExpanded,
  onToggle,
}: {
  log: LogRow;
  isExpanded: boolean;
  onToggle: () => void;
}) => {
  const hasAttributes =
    log.attributes != null && Object.keys(log.attributes).length > 0;
  const hasLongBody = log.body.length > 80;
  const canExpand = hasAttributes || hasLongBody;
  return (
    <Box
      sx={{
        px: 2,
        py: 0.75,
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Stack
        direction="row"
        spacing={1}
        sx={{ alignItems: 'flex-start' }}
        useFlexGap
      >
        <Typography
          variant="caption"
          sx={{
            fontFamily: 'monospace',
            color: 'text.secondary',
            whiteSpace: 'nowrap',
            mt: '2px',
          }}
        >
          {formatWallClock(log.timestamp)}
        </Typography>
        {(log.severityText || log.severityNumber != null) && (
          <Chip
            label={log.severityText ?? severityLabel(log.severityNumber)}
            size="small"
            color={severityColor(log.severityNumber)}
            variant="outlined"
            sx={{
              height: 16,
              fontSize: '0.6rem',
              mt: '2px',
              '& .MuiChip-label': { px: 0.75 },
            }}
          />
        )}
        <Typography
          variant="caption"
          sx={{
            fontFamily: 'monospace',
            fontSize: '0.7rem',
            flex: 1,
            minWidth: 0,
            overflow: 'hidden',
            textOverflow: isExpanded ? 'clip' : 'ellipsis',
            whiteSpace: isExpanded ? 'pre-wrap' : 'nowrap',
            wordBreak: isExpanded ? 'break-word' : 'normal',
          }}
        >
          {log.body}
        </Typography>
        {canExpand && (
          <IconButton size="small" onClick={onToggle} sx={{ p: 0.25 }}>
            {isExpanded ? (
              <ExpandLessIcon fontSize="small" />
            ) : (
              <ExpandMoreIcon fontSize="small" />
            )}
          </IconButton>
        )}
      </Stack>
      {isExpanded && hasAttributes && (
        <Box sx={{ mt: 0.5, ml: 1 }}>
          <AttributeList attributes={log.attributes!} fontSize="0.65rem" />
        </Box>
      )}
    </Box>
  );
};

interface SpanRowLabelProps {
  span: SpanRow;
  spanIndex: number;
  traceWindow: TraceWindow;
  isSelected: boolean;
  errorCountBelow: number;
  selfTimeNanos: number;
  onSelect: () => void;
}

const SpanRowLabel = ({
  span,
  spanIndex,
  traceWindow,
  isSelected,
  errorCountBelow,
  selfTimeNanos,
  onSelect,
}: SpanRowLabelProps) => {
  const startMs = Date.parse(span.startTimestamp);
  const durationMs = (span.durationNanos ?? 0) / NANOS_PER_MILLI;
  const offsetPct =
    ((startMs - traceWindow.earliestStartMs) / traceWindow.totalMs) * 100;
  const widthPct = Math.max(
    (durationMs / traceWindow.totalMs) * 100,
    MIN_BAR_WIDTH_PCT,
  );
  const isError = span.statusCode === 'error';
  const durationLabelSx = {
    position: 'absolute',
    right: `${100 - offsetPct}%`,
    top: '50%',
    transform: 'translateY(-50%)',
    pr: 0.5,
  };

  // selfTimeNanos is passed through for use by SpanDetailPanel; suppress the
  // unused-variable lint warning by referencing it in a no-op way here.
  void selfTimeNanos;

  return (
    <Box
      onClick={onSelect}
      sx={{
        cursor: 'pointer',
        bgcolor: isSelected ? 'action.selected' : 'transparent',
      }}
    >
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: `${NAME_COLUMN_WIDTH} 1fr`,
          alignItems: 'center',
          height: SPAN_ROW_HEIGHT_PX,
        }}
      >
        <Stack
          direction="row"
          spacing={0.75}
          sx={{ pr: 1, overflow: 'hidden', alignItems: 'center' }}
        >
          <Chip
            label={spanIndex}
            size="small"
            variant="outlined"
            sx={{
              height: 16,
              minWidth: 20,
              fontSize: '0.6rem',
              fontWeight: 'bold',
              '& .MuiChip-label': { px: 0.5 },
            }}
          />
          {span.kind && (
            <Chip
              label={span.kind}
              size="small"
              variant="outlined"
              sx={{
                height: 16,
                fontSize: '0.6rem',
                '& .MuiChip-label': { px: 0.75 },
              }}
            />
          )}
          <Typography
            variant="body2"
            sx={{
              fontFamily: 'monospace',
              fontSize: '0.75rem',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
            title={span.name}
          >
            {span.name}
          </Typography>
          {(() => {
            const breakdown = tokenBreakdownForSpan(span);
            if (breakdown.total === 0) {
              return null;
            }
            return (
              <Tooltip
                title={
                  <Box>
                    <Box
                      sx={{
                        fontWeight: 'bold',
                        fontSize: '0.75rem',
                        mb: 0.5,
                      }}
                    >
                      Tokens
                    </Box>
                    <Box
                      sx={{
                        fontFamily: 'monospace',
                        fontSize: '0.7rem',
                        display: 'grid',
                        gridTemplateColumns: 'auto auto',
                        columnGap: 1,
                        rowGap: 0.25,
                      }}
                    >
                      <span>Input:</span>
                      <span>{breakdown.input.toLocaleString()}</span>
                      <span>Output:</span>
                      <span>{breakdown.output.toLocaleString()}</span>
                      <span>Cache Create:</span>
                      <span>{breakdown.cacheCreate.toLocaleString()}</span>
                      <span>Cache Read:</span>
                      <span>{breakdown.cacheRead.toLocaleString()}</span>
                      <Box sx={{ fontWeight: 'bold' }}>Total:</Box>
                      <Box sx={{ fontWeight: 'bold' }}>
                        {breakdown.total.toLocaleString()}
                      </Box>
                    </Box>
                  </Box>
                }
              >
                <Chip
                  label={breakdown.total.toLocaleString()}
                  size="small"
                  sx={{
                    height: 16,
                    fontSize: '0.6rem',
                    bgcolor: 'warning.light',
                    color: 'warning.contrastText',
                    '& .MuiChip-label': { px: 0.75 },
                  }}
                />
              </Tooltip>
            );
          })()}
          {isError && (
            <Tooltip title={span.statusMessage ?? 'error'}>
              <Chip
                label="error"
                size="small"
                color="error"
                variant="outlined"
                sx={{
                  height: 16,
                  fontSize: '0.6rem',
                  '& .MuiChip-label': { px: 0.75 },
                }}
              />
            </Tooltip>
          )}
          {errorCountBelow > 0 && (
            <Tooltip
              title={`${errorCountBelow} error${errorCountBelow === 1 ? '' : 's'} in descendant spans`}
            >
              <Chip
                label={`+${errorCountBelow} below`}
                size="small"
                color="warning"
                variant="outlined"
                sx={{
                  height: 16,
                  fontSize: '0.6rem',
                  '& .MuiChip-label': { px: 0.75 },
                }}
              />
            </Tooltip>
          )}
        </Stack>

        <Box sx={{ position: 'relative', height: '100%', mx: 1 }}>
          <Typography
            variant="caption"
            sx={{
              ...durationLabelSx,
              fontFamily: 'monospace',
              fontSize: '0.65rem',
              color: 'text.secondary',
              whiteSpace: 'nowrap',
              pointerEvents: 'none',
            }}
          >
            {formatDuration(span.durationNanos)}
          </Typography>
          <Box
            sx={{
              position: 'absolute',
              left: `${offsetPct}%`,
              width: `${widthPct}%`,
              top: '50%',
              transform: 'translateY(-50%)',
              height: 14,
              borderRadius: 0.5,
              bgcolor: isError ? 'error.main' : 'primary.main',
              opacity: 0.8,
              pointerEvents: 'none',
            }}
          />
        </Box>
      </Box>
    </Box>
  );
};

const SpanDetailPanel = ({
  span,
  selfTimeNanos,
  onClose,
}: {
  span: SpanRow;
  selfTimeNanos: number;
  onClose: () => void;
}) => (
  <Card
    variant="outlined"
    sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}
  >
    <Stack
      direction="row"
      sx={{
        p: 1.5,
        borderBottom: 1,
        borderColor: 'divider',
        alignItems: 'flex-start',
      }}
    >
      <Typography
        variant="body2"
        sx={{
          fontFamily: 'monospace',
          fontSize: '0.75rem',
          fontWeight: 600,
          flex: 1,
          wordBreak: 'break-all',
        }}
      >
        {span.name}
      </Typography>
      <IconButton size="small" onClick={onClose} sx={{ ml: 1, flexShrink: 0 }}>
        <CloseIcon fontSize="small" />
      </IconButton>
    </Stack>
    <Box sx={{ flex: 1, overflowY: 'auto', p: 1.5 }}>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'auto 1fr',
          columnGap: 1,
          rowGap: 0.25,
          mb: 1,
          fontFamily: 'monospace',
          fontSize: '0.7rem',
          color: 'text.secondary',
        }}
      >
        <Box component="span">span id:</Box>
        <Box component="span" sx={{ color: 'text.primary', wordBreak: 'break-all' }}>
          {span.spanId}
        </Box>
        <Box component="span">started:</Box>
        <Box component="span" sx={{ color: 'text.primary' }}>
          {formatWallClock(span.startTimestamp)}
        </Box>
        <Box component="span">ended:</Box>
        <Box component="span" sx={{ color: 'text.primary' }}>
          {formatWallClock(span.endTimestamp)}
        </Box>
        {selfTimeNanos !== (span.durationNanos ?? 0) && (
          <>
            <Box component="span">self time:</Box>
            <Box component="span" sx={{ color: 'text.primary' }}>
              {formatDuration(selfTimeNanos)} of{' '}
              {formatDuration(span.durationNanos)} total
            </Box>
          </>
        )}
      </Box>
      {span.statusMessage && (
        <Box sx={{ mb: 0.75 }}>
          <Typography
            variant="caption"
            sx={{
              fontFamily: 'monospace',
              fontSize: '0.7rem',
              color: 'error.main',
              display: 'block',
            }}
          >
            status: {span.statusMessage}
          </Typography>
        </Box>
      )}
      {span.scopeName && (
        <Typography
          variant="caption"
          sx={{
            fontFamily: 'monospace',
            fontSize: '0.7rem',
            color: 'text.secondary',
            display: 'block',
            mb: 0.5,
          }}
        >
          scope: {span.scopeName}
        </Typography>
      )}
      {span.attributes && Object.keys(span.attributes).length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Divider
            textAlign="left"
            sx={{
              mb: 0.5,
              '& .MuiDivider-wrapper': { fontSize: '0.65rem', px: 1 },
            }}
          >
            Attributes ({Object.keys(span.attributes).length})
          </Divider>
          <Box sx={{ pl: 1 }}>
            <AttributeList attributes={span.attributes} fontSize="0.7rem" />
          </Box>
        </Box>
      )}
      {span.events && span.events.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Divider
            textAlign="left"
            sx={{
              mb: 0.5,
              '& .MuiDivider-wrapper': { fontSize: '0.65rem', px: 1 },
            }}
          >
            Events ({span.events.length})
          </Divider>
          {span.events.map((event, index) => (
            <Box key={index} sx={{ mb: 0.75, pl: 1 }}>
              <Typography
                variant="caption"
                sx={{
                  fontFamily: 'monospace',
                  fontSize: '0.7rem',
                  display: 'block',
                }}
              >
                <Box component="span" sx={{ color: 'text.secondary', mr: 1 }}>
                  T+{formatDuration(eventOffsetNanos(event, span))}
                </Box>
                {event.name}
              </Typography>
              {event.attributes && Object.keys(event.attributes).length > 0 && (
                <Box sx={{ mt: 0.25, ml: 1 }}>
                  <AttributeList
                    attributes={event.attributes}
                    fontSize="0.65rem"
                  />
                </Box>
              )}
            </Box>
          ))}
        </Box>
      )}
    </Box>
  </Card>
);

interface RenderSpanTreeArgs {
  span: SpanRow;
  spanIndices: Map<string, number>;
  childrenByParentId: Map<string, SpanRow[]>;
  traceWindow: TraceWindow;
  selectedSpanId: string | null;
  descendantErrorCounts: Map<string, number>;
  selfTimeNanosBySpanId: Map<string, number>;
  onSelectSpan: (spanId: string) => void;
}

const renderSpanTreeItem = ({
  span,
  spanIndices,
  childrenByParentId,
  traceWindow,
  selectedSpanId,
  descendantErrorCounts,
  selfTimeNanosBySpanId,
  onSelectSpan,
}: RenderSpanTreeArgs) => {
  const children = childrenByParentId.get(span.spanId) ?? [];

  return (
    <CustomTreeItem
      key={span.id}
      itemId={span.spanId}
      label={
        <SpanRowLabel
          span={span}
          spanIndex={spanIndices.get(span.spanId) ?? 0}
          traceWindow={traceWindow}
          isSelected={selectedSpanId === span.spanId}
          errorCountBelow={descendantErrorCounts.get(span.spanId) ?? 0}
          selfTimeNanos={selfTimeNanosBySpanId.get(span.spanId) ?? 0}
          onSelect={() => onSelectSpan(span.spanId)}
        />
      }
    >
      {children.map((child) =>
        renderSpanTreeItem({
          span: child,
          spanIndices,
          childrenByParentId,
          traceWindow,
          selectedSpanId,
          descendantErrorCounts,
          selfTimeNanosBySpanId,
          onSelectSpan,
        }),
      )}
    </CustomTreeItem>
  );
};

const WaterfallAxis = ({ totalMs }: { totalMs: number }) => {
  const ticks = [0, 0.25, 0.5, 0.75, 1];
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: `calc(${NAME_COLUMN_WIDTH} + 24px) 1fr`,
        alignItems: 'center',
        height: 24,
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Box sx={{ pl: 1 }}>
        <Typography variant="caption" color="text.secondary">
          Span
        </Typography>
      </Box>
      <Box sx={{ position: 'relative', height: '100%', mx: 1 }}>
        {ticks.map((tick) => (
          <Typography
            key={tick}
            variant="caption"
            color="text.secondary"
            sx={{
              position: 'absolute',
              left: `${tick * 100}%`,
              top: '50%',
              transform:
                tick === 1 ? 'translate(-100%, -50%)' : 'translate(-50%, -50%)',
              fontSize: '0.65rem',
              fontFamily: 'monospace',
              whiteSpace: 'nowrap',
            }}
          >
            {formatDuration(tick * totalMs * NANOS_PER_MILLI)}
          </Typography>
        ))}
      </Box>
    </Box>
  );
};

export interface TraceDetailPageViewProps {
  traceId: string;
  spans: SpanRow[] | undefined;
  isLoading: boolean;
  error: Error | null;
  tree: SpanTree;
  spanIndices: Map<string, number>;
  traceWindow: TraceWindow | null;
  parentSpanIds: string[];
  descendantErrorCounts: Map<string, number>;
  selfTimeNanosBySpanId: Map<string, number>;
  sessionId: string | null;
  showLogs: boolean;
  onShowLogsChange: (next: boolean) => void;
  logs: LogRow[];
}

export default function TraceDetailPageView({
  traceId,
  spans,
  isLoading,
  error,
  tree,
  spanIndices,
  traceWindow,
  parentSpanIds,
  descendantErrorCounts,
  selfTimeNanosBySpanId,
  sessionId,
  showLogs,
  onShowLogsChange,
  logs,
}: TraceDetailPageViewProps) {
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null);
  const [expandedItems, setExpandedItems] = useState<string[]>([]);
  const [collapsedLogIds, setCollapsedLogIds] = useState<Set<string>>(
    () => new Set(),
  );
  const treeInitialized = useRef(false);

  useEffect(() => {
    if (!treeInitialized.current && parentSpanIds.length > 0) {
      treeInitialized.current = true;
      setExpandedItems(parentSpanIds);
    }
  }, [parentSpanIds]);

  const selectedSpan =
    selectedSpanId != null
      ? (spans?.find((s) => s.spanId === selectedSpanId) ?? null)
      : null;

  const handleSelectSpan = (spanId: string) => {
    setSelectedSpanId((previous) => (previous === spanId ? null : spanId));
  };

  const titleContent = (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline' }} useFlexGap>
      <Link component={RouterLink} to="/traces" underline="hover" color="primary">
        Traces
      </Link>
      <Box component="span" sx={{ color: 'text.secondary', fontWeight: 'normal' }}>
        ›
      </Box>
      <Box component="span">Trace Detail</Box>
      <Chip label={traceId} variant="outlined" size="small" />
      {traceWindow && spans && (
        <Chip
          label={`${spans.length} ${spans.length === 1 ? 'span' : 'spans'}`}
          size="small"
          sx={{ bgcolor: 'action.selected', color: 'primary.main' }}
        />
      )}
    </Stack>
  );

  return (
    <PageLayout
      title={titleContent}
      error={error}
      actions={
        <>
          {parentSpanIds.length > 0 && (
            <Button
              size="small"
              variant="outlined"
              onClick={() => {
                if (expandedItems.length === parentSpanIds.length) {
                  setExpandedItems([]);
                } else {
                  setExpandedItems(parentSpanIds);
                }
              }}
              startIcon={
                expandedItems.length === parentSpanIds.length ? (
                  <UnfoldLessIcon />
                ) : (
                  <UnfoldMoreIcon />
                )
              }
              sx={{ fontSize: '0.7rem', textTransform: 'none' }}
            >
              {expandedItems.length === parentSpanIds.length
                ? 'Collapse all'
                : 'Expand all'}
            </Button>
          )}
          {sessionId && (
            <Button
              size="small"
              variant="outlined"
              startIcon={<ArticleIcon />}
              onClick={() => onShowLogsChange(true)}
              sx={{ fontSize: '0.7rem', textTransform: 'none' }}
            >
              View Logs
            </Button>
          )}
        </>
      }
    >
      {isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress size={20} />
        </Box>
      )}
      {!isLoading && spans && spans.length === 0 && (
        <Typography variant="body2" sx={{ p: 2, color: 'text.secondary' }}>
          No spans found for this trace.
        </Typography>
      )}
      {spans && spans.length > 0 && traceWindow && (
        <Box sx={{ display: 'flex', gap: 2, alignItems: 'flex-start' }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Card variant="outlined" sx={{ overflowY: 'auto', p: 1 }}>
              <WaterfallAxis totalMs={traceWindow.totalMs} />
              <SimpleTreeView
                expansionTrigger="iconContainer"
                expandedItems={expandedItems}
                onExpandedItemsChange={(_event, itemIds) =>
                  setExpandedItems(itemIds)
                }
                slots={{
                  expandIcon: ExpandIcon,
                  collapseIcon: CollapseIcon,
                  endIcon: EndIcon,
                }}
              >
                {tree.roots.map((root) =>
                  renderSpanTreeItem({
                    span: root,
                    spanIndices,
                    childrenByParentId: tree.childrenByParentId,
                    traceWindow,
                    selectedSpanId,
                    descendantErrorCounts,
                    selfTimeNanosBySpanId,
                    onSelectSpan: handleSelectSpan,
                  }),
                )}
              </SimpleTreeView>
            </Card>
          </Box>
          {selectedSpan && (
            <Box sx={{ width: 360, flexShrink: 0 }}>
              <SpanDetailPanel
                span={selectedSpan}
                selfTimeNanos={selfTimeNanosBySpanId.get(selectedSpan.spanId) ?? 0}
                onClose={() => setSelectedSpanId(null)}
              />
            </Box>
          )}
        </Box>
      )}
      <Box sx={{ pt: 2 }}>
        <Link component={RouterLink} to="/traces" underline="hover" color="primary">
          ← Back to Traces
        </Link>
      </Box>
      <Drawer
        anchor="right"
        open={showLogs}
        onClose={() => onShowLogsChange(false)}
        sx={{ zIndex: (t) => t.zIndex.modal }}
        slotProps={{
          paper: {
            sx: { width: 650, display: 'flex', flexDirection: 'column' },
          },
        }}
      >
        <Stack
          direction="row"
          sx={{
            alignItems: 'center',
            px: 2,
            py: 1.5,
            borderBottom: 1,
            borderColor: 'divider',
          }}
          useFlexGap
        >
          <Typography variant="subtitle1" sx={{ fontWeight: 600, flex: 1 }}>
            Logs {logs.length > 0 ? `(${logs.length})` : ''}
          </Typography>
          {logs.length > 0 && (
            <Button
              size="small"
              onClick={() => {
                if (collapsedLogIds.size === 0) {
                  setCollapsedLogIds(new Set(logs.map((l) => String(l.id))));
                } else {
                  setCollapsedLogIds(new Set());
                }
              }}
              startIcon={
                collapsedLogIds.size === 0 ? (
                  <UnfoldLessIcon />
                ) : (
                  <UnfoldMoreIcon />
                )
              }
              sx={{ fontSize: '0.7rem', textTransform: 'none', mr: 0.5 }}
            >
              {collapsedLogIds.size === 0 ? 'Collapse all' : 'Expand all'}
            </Button>
          )}
          <IconButton size="small" onClick={() => onShowLogsChange(false)}>
            <CloseIcon fontSize="small" />
          </IconButton>
        </Stack>
        <Box sx={{ flex: 1, overflowY: 'auto' }}>
          {logs.length === 0 ? (
            <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>
              No logs found for this trace.
            </Typography>
          ) : (
            logs.map((log) => (
              <LogDrawerRow
                key={log.id}
                log={log}
                isExpanded={!collapsedLogIds.has(String(log.id))}
                onToggle={() => {
                  setCollapsedLogIds((prev) => {
                    const next = new Set(prev);
                    if (next.has(String(log.id))) {
                      next.delete(String(log.id));
                    } else {
                      next.add(String(log.id));
                    }
                    return next;
                  });
                }}
              />
            ))
          )}
        </Box>
      </Drawer>
    </PageLayout>
  );
}
