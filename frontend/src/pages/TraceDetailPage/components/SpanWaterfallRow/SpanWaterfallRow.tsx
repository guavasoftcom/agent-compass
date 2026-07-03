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

// Billable-token and cache-read pills shown after the span name.
const SpanTokenBadges = ({ tokens }: { tokens: TokenBreakdown }) => (
  <>
    {tokens.input + tokens.output + tokens.cacheCreate > 0 ? (
      <Tooltip
        arrow
        placement="top"
        title={
          <Box sx={{ py: 0.5, typography: 'mono' }}>
            <Box sx={{ fontSize: 11.5, fontWeight: 700, mb: 0.3 }}>
              {formatTokens(tokens.input + tokens.output + tokens.cacheCreate)}
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
                Cache creation
              </Box>
              <Box component="span" sx={{ textAlign: 'right' }}>
                {formatTokens(tokens.cacheCreate)}
              </Box>
              <Box component="span" sx={{ opacity: 0.75 }}>
                Output
              </Box>
              <Box component="span" sx={{ textAlign: 'right' }}>
                {formatTokens(tokens.output)}
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
          {formatTokens(tokens.input + tokens.output + tokens.cacheCreate)}
        </Box>
      </Tooltip>
    ) : null}
    {tokens.cacheRead > 0 ? (
      <Tooltip
        arrow
        placement="top"
        title={
          <Box sx={{ py: 0.5, typography: 'mono' }}>
            <Box sx={{ fontSize: 11.5, fontWeight: 700, mb: 0.3 }}>
              {tokens.cacheRead.toLocaleString()} cache read
            </Box>
            <Box sx={{ fontSize: 10, opacity: 0.7 }}>
              Billed at ~1/10 the input rate, so kept separate
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
            color: (t) =>
              `color-mix(in srgb, ${t.palette.info.main} 88%, ${t.palette.text.secondary})`,
            bgcolor: (t) => alpha(t.palette.info.main, 0.14),
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
            <ellipse cx="12" cy="5" rx="8" ry="3" />
            <path d="M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" />
          </Box>
          {formatTokens(tokens.cacheRead)}
        </Box>
      </Tooltip>
    ) : null}
  </>
);

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
        {span.kind ? (
          <Box
            component="span"
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              height: 16,
              px: 0.75,
              mr: 0.9,
              borderRadius: '4px',
              bgcolor: (t) =>
                t.palette.mode === 'dark'
                  ? alpha(neutralColors.white, 0.08)
                  : alpha(neutralColors.inkLight, 0.08),
              color: 'text.disabled',
              typography: 'mono',
              fontSize: 9.5,
              fontWeight: 500,
              flexShrink: 0,
            }}
          >
            {span.kind}
          </Box>
        ) : null}
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
