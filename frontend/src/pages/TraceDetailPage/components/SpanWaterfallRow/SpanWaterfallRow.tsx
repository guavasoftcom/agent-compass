import { Box, Tooltip, alpha, useTheme } from '@mui/material';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import { neutralColors } from '../../../../theme/colors';
import type { SpanRow } from '../../../../api';
import { formatDuration, formatTokens } from '../../../TracesPage/tracesApi';
import {
  tokenBreakdownForSpan,
  type TokenBreakdown,
} from '../../../TracesPage/tokenBreakdown';
import { fontFamilies } from '../../../../theme/typography';

interface Props {
  span: SpanRow;
  depth: number;
  hasChildren: boolean;
  isCollapsed: boolean;
  isSelected: boolean;
  indexLabel: number | undefined;
  descendantErrorCount: number;
  logCount: number;
  gridColumns: string;
  // Horizontal bar geometry (percent of the visible zoom window).
  left: number;
  right: number;
  width: number;
  onToggleCollapse: (spanId: string) => void;
  onSelect: (spanId: string) => void;
}

// Aurora sync: combined billable + cache-read into a single total badge (the
// two separate pills duplicated the same figures shown in the span detail
// dock's Tokens section). Full breakdown, including cache read, stays one
// hover away in the tooltip.
const SpanTokenBadges = ({ tokens }: { tokens: TokenBreakdown }) => {
  const total = tokens.input + tokens.output + tokens.cacheCreate + tokens.cacheRead;
  if (total <= 0) {
    return null;
  }
  return (
    <Tooltip
      arrow
      placement="top"
      title={
        <Box sx={{ py: 0.5, typography: 'mono' }}>
          <Box sx={{ fontSize: 11.5, fontWeight: 700, mb: 0.3 }}>
            {formatTokens(total)} total tokens
          </Box>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: 'auto auto',
              columnGap: 1.5,
              rowGap: 0.3,
              fontSize: 11,
            }}
          >
            <Box component="span" sx={{ opacity: 0.75 }}>
              Input
            </Box>
            <Box component="span" sx={{ textAlign: 'right' }}>
              {formatTokens(tokens.input)}
            </Box>
            <Box component="span" sx={{ opacity: 0.75 }}>
              Output
            </Box>
            <Box component="span" sx={{ textAlign: 'right' }}>
              {formatTokens(tokens.output)}
            </Box>
            <Box component="span" sx={{ opacity: 0.75 }}>
              Cache creation
            </Box>
            <Box component="span" sx={{ textAlign: 'right' }}>
              {formatTokens(tokens.cacheCreate)}
            </Box>
            <Box component="span" sx={{ opacity: 0.75 }}>
              Cache read
            </Box>
            <Box component="span" sx={{ textAlign: 'right' }}>
              {formatTokens(tokens.cacheRead)}
            </Box>
          </Box>
        </Box>
      }
    >
      <Box
        component="span"
        sx={{
          ml: 0.9,
          display: 'inline-flex',
          alignItems: 'center',
          gap: 0.4,
          px: 0.75,
          height: 17,
          borderRadius: '5px',
          color: 'warning.main',
          bgcolor: (t) => alpha(t.palette.warning.main, 0.16),
          typography: 'mono',
          fontSize: 10,
          fontWeight: 600,
          flexShrink: 0,
        }}
      >
        <Box
          component="svg"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={2}
          sx={{ width: 10, height: 10 }}
        >
          <circle cx="12" cy="12" r="8" />
          <circle cx="12" cy="12" r="3" fill="currentColor" />
        </Box>
        {formatTokens(total)}
      </Box>
    </Tooltip>
  );
};

const SpanWaterfallRow = ({
  span,
  depth,
  hasChildren,
  isCollapsed,
  isSelected,
  indexLabel,
  descendantErrorCount,
  logCount,
  gridColumns,
  left,
  right,
  width,
  onToggleCollapse,
  onSelect,
}: Props) => {
  const theme = useTheme();
  const tokens = tokenBreakdownForSpan(span);
  // Aurora sync: dropped the per-row `kind` pill (nearly every span is
  // `internal`, so it repeated without informing — `kind` still shows once in
  // the detail dock's meta grid) and replaced it with the span's tool name,
  // which is what actually distinguishes one tool-call row from the next.
  const toolNameAttribute = span.attributes?.['tool_name'];
  const toolName = typeof toolNameAttribute === 'string' ? toolNameAttribute : '';
  const isError = span.statusCode === 'error';
  const barBackground = isError
    ? theme.palette.error.main
    : `linear-gradient(90deg, ${theme.palette.primary.main}, ${theme.palette.primary.light})`;

  // Duration-label placement, kept inside the track so it can never force a
  // horizontal scrollbar: after the bar when it ends with room to spare, just
  // before the bar's start when the bar sits to the right, and — for a
  // full-width bar like the root span, where neither side has room — tucked
  // inside the bar's right end with light text so it reads as a deliberate
  // on-bar label rather than overlapping in the dim body color.
  const labelInsideBar = right >= 85;
  const durationLabelStyle = labelInsideBar
    ? { right: `calc(${100 - right}% + 8px)`, color: neutralColors.white }
    : { left: `calc(${right}% + 6px)` };
  return (
    <Box
      data-span={span.spanId}
      onClick={() => onSelect(span.spanId)}
      sx={{
        display: 'grid',
        gridTemplateColumns: gridColumns,
        alignItems: 'center',
        height: 30,
        borderBottom: 1,
        borderColor: 'divider',
        cursor: 'pointer',
        opacity: 1,
        bgcolor: isSelected
          ? (t) =>
              alpha(
                t.palette.primary.main,
                t.palette.mode === 'dark' ? 0.22 : 0.12,
              )
          : 'transparent',
        boxShadow: isSelected
          ? (t) => `inset 2px 0 0 ${t.palette.primary.main}`
          : 'none',
        transition: 'opacity .14s, background .1s',
        '&:hover': { bgcolor: 'action.hover' },
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          minWidth: 0,
          pl: `${10 + depth * 15}px`,
        }}
      >
        {hasChildren ? (
          <Box
            component="span"
            onClick={(e) => {
              e.stopPropagation();
              onToggleCollapse(span.spanId);
            }}
            sx={{
              display: 'grid',
              placeItems: 'center',
              width: 18,
              height: 18,
              color: 'text.disabled',
              transform: isCollapsed ? 'none' : 'rotate(90deg)',
              transition: 'transform .12s',
              '& svg': { fontSize: 15 },
            }}
          >
            <ChevronRightIcon />
          </Box>
        ) : (
          <Box sx={{ width: 18, display: 'grid', placeItems: 'center' }}>
            <Box
              sx={{
                width: 5,
                height: 5,
                borderRadius: '50%',
                bgcolor: 'text.disabled',
                opacity: 0.5,
              }}
            />
          </Box>
        )}
        <Box
          component="span"
          sx={{
            display: 'inline-grid',
            placeItems: 'center',
            minWidth: 19,
            height: 17,
            px: 0.6,
            mr: 0.9,
            borderRadius: '5px',
            border: 1,
            borderColor: 'divider',
            typography: 'mono',
            fontSize: 10,
            fontWeight: 600,
            color: 'text.secondary',
            flexShrink: 0,
          }}
        >
          {indexLabel}
        </Box>
        <Box
          component="span"
          sx={{
            typography: 'mono',
            fontSize: 12.5,
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
          title={span.name}
        >
          {span.name}
        </Box>
        {toolName ? (
          <Box
            component="span"
            sx={{
              ml: 0.9,
              display: 'inline-flex',
              alignItems: 'center',
              height: 17,
              px: 0.75,
              borderRadius: '5px',
              color: 'info.main',
              bgcolor: (t) => alpha(t.palette.info.main, 0.15),
              typography: 'mono',
              fontSize: 10,
              fontWeight: 600,
              flexShrink: 0,
            }}
          >
            {toolName}
          </Box>
        ) : null}
        <SpanTokenBadges tokens={tokens} />
        {isError ? (
          <Box
            component="span"
            sx={{
              ml: 0.9,
              px: 0.75,
              py: 0.1,
              borderRadius: '5px',
              color: 'error.main',
              bgcolor: (t) => alpha(t.palette.error.main, 0.14),
              fontFamily: fontFamilies.display,
              fontSize: 9.5,
              fontWeight: 700,
              flexShrink: 0,
            }}
          >
            error
          </Box>
        ) : null}
        {descendantErrorCount > 0 ? (
          <Box
            component="span"
            sx={{
              ml: 0.9,
              px: 0.75,
              py: 0.1,
              borderRadius: '5px',
              color: 'warning.main',
              bgcolor: (t) => alpha(t.palette.warning.main, 0.14),
              fontFamily: fontFamilies.display,
              fontSize: 9.5,
              fontWeight: 700,
              flexShrink: 0,
            }}
          >
            +{descendantErrorCount} below
          </Box>
        ) : null}
        {logCount > 0 ? (
          <Box
            component="span"
            sx={{
              ml: 0.9,
              px: 0.75,
              py: 0.1,
              borderRadius: '5px',
              color: 'info.main',
              bgcolor: (t) => alpha(t.palette.info.main, 0.14),
              fontFamily: fontFamilies.display,
              fontSize: 9.5,
              fontWeight: 700,
              flexShrink: 0,
            }}
          >
            {logCount} log{logCount === 1 ? '' : 's'}
          </Box>
        ) : null}
      </Box>
      <Box sx={{ position: 'relative', height: '100%', mx: 1.5 }}>
        {width > 0 && right > 0 && left < 100 ? (
          <>
            <Box
              sx={{
                position: 'absolute',
                top: '50%',
                transform: 'translateY(-50%)',
                height: 13,
                borderRadius: '4px',
                background: barBackground,
                opacity: isSelected ? 1 : 0.82,
                left: `${left}%`,
                width: `${width}%`,
                minWidth: 3,
              }}
            />
            <Box
              component="span"
              sx={{
                position: 'absolute',
                top: '50%',
                transform: 'translateY(-50%)',
                typography: 'mono',
                fontSize: 10,
                color: 'text.secondary',
                whiteSpace: 'nowrap',
                pointerEvents: 'none',
                ...durationLabelStyle,
              }}
            >
              {formatDuration(span.durationNanos)}
            </Box>
          </>
        ) : null}
      </Box>
    </Box>
  );
};

export default SpanWaterfallRow;
