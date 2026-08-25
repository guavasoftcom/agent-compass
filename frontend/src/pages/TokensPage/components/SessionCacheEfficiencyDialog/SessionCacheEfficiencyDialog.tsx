import { useState } from 'react';
import {
  Box,
  Button,
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
  Stack,
  Tooltip,
} from '@mui/material';
import { alpha, useTheme } from '@mui/material/styles';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import CloseIcon from '@mui/icons-material/Close';
import { Link as RouterLink } from 'react-router-dom';
import type { SessionCacheEfficiencyRow } from '../../../../api';
import {
  cacheEfficiencyBand,
  formatCacheEfficiency,
} from '../../../../lib/cacheEfficiency';
import {
  USD_FORMATTER,
  formatCompact,
  formatRelativeTime,
  formatTimestamp,
} from '../../../../lib/format';
import { radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import { sessionsDeepLink } from '../../../SessionsPage/SessionsPage';
import {
  CACHE_EFFICIENCY_BAND_CHIP_LABELS,
  cacheEfficiencyBandColor,
} from '../cacheEfficiencyBandColors';
import { TOKEN_KIND_COLORS, TOKEN_KIND_LABELS } from '../../tokenKindColors';

export interface SessionCacheEfficiencyDialogProps {
  /** The selected row, or null when the dialog is closed. */
  row: SessionCacheEfficiencyRow | null;
  onClose: () => void;
}

/** Opacity of the band color behind the header chip. */
const BAND_CHIP_TINT = 0.16;

/** Smallest visible slice of the token bar, so a tiny segment still reads. */
const MINIMUM_SEGMENT_WIDTH = '2px';

const KpiTile = ({
  label,
  value,
  color,
}: {
  label: string;
  value: string;
  color?: string;
}) => (
  <Box>
    <Box sx={{ typography: 'eyebrowSm', color: 'text.disabled' }}>{label}</Box>
    <Box
      sx={{
        mt: 0.6,
        fontFamily: fontFamilies.display,
        fontWeight: 800,
        fontSize: 24,
        color: color ?? 'text.primary',
      }}
    >
      {value}
    </Box>
  </Box>
);

interface DialogBodyProps {
  row: SessionCacheEfficiencyRow;
  onClose: () => void;
}

/**
 * The dialog's content, split out so it only ever renders against a real,
 * non-null row — no fabricated `row?.` fallbacks. `SessionCacheEfficiencyDialog`
 * renders this against `row ?? lastRow` so the body keeps showing the last
 * selection while the Dialog's exit transition runs (see the `lastRow` note
 * on the outer component).
 */
const DialogBody = ({ row, onClose }: DialogBodyProps) => {
  const theme = useTheme();
  const band = cacheEfficiencyBand(row.cacheEfficiency);
  const bandColor = cacheEfficiencyBandColor(band, theme);

  // Shared with the Overview tab's donut and trend chart via TOKEN_KIND_COLORS,
  // so a token kind keeps one color across the whole page, not just within
  // this dialog.
  const segments = [
    {
      label: TOKEN_KIND_LABELS.cacheRead,
      value: row.cacheReadTokens,
      color: TOKEN_KIND_COLORS.cacheRead,
    },
    {
      label: TOKEN_KIND_LABELS.input,
      value: row.inputTokens,
      color: TOKEN_KIND_COLORS.input,
    },
    {
      label: TOKEN_KIND_LABELS.cacheCreation,
      value: row.cacheCreationTokens,
      color: TOKEN_KIND_COLORS.cacheCreation,
    },
    {
      label: TOKEN_KIND_LABELS.output,
      value: row.outputTokens,
      color: TOKEN_KIND_COLORS.output,
    },
  ];
  // The bar shows the session's whole token mix, so its denominator is all four
  // kinds — NOT the KPI tile's inputSideTokens. Read as its own sum rather than
  // from row.totalTokens so a contract drift shows up as segments that don't
  // fill the track instead of as overflow.
  const segmentTotal = segments.reduce(
    (running, segment) => running + segment.value,
    0,
  );
  // A zero-value segment still gets a legend row (reading "0"), but is dropped
  // before the bar chips: the MINIMUM_SEGMENT_WIDTH floor would otherwise paint
  // a visible sliver for a value that isn't actually present.
  const nonZeroSegments = segments.filter((segment) => segment.value > 0);

  return (
    <>
      <DialogTitle sx={{ pr: 6, pb: 2 }}>
        <Box
          sx={{
            fontFamily: fontFamilies.mono,
            fontSize: 14,
            fontWeight: 700,
            wordBreak: 'break-all',
          }}
        >
          {row.sessionId}
        </Box>
        {row.endTimestamp ? (
          <Tooltip
            title={formatTimestamp(row.endTimestamp)}
            placement="top"
            arrow
          >
            <Box
              component="span"
              sx={{
                display: 'inline-block',
                mt: 0.5,
                fontSize: 12,
                color: 'text.secondary',
              }}
            >
              Last activity {formatRelativeTime(row.endTimestamp)}
            </Box>
          </Tooltip>
        ) : null}
        <Box
          sx={{
            mt: 1.1,
            ml: 1,
            display: 'inline-flex',
            alignItems: 'center',
            height: 22,
            px: 1.25,
            borderRadius: radii.pill,
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.3px',
            color: bandColor,
            bgcolor: alpha(bandColor, BAND_CHIP_TINT),
          }}
        >
          {CACHE_EFFICIENCY_BAND_CHIP_LABELS[band]}
        </Box>
        <IconButton
          aria-label="close"
          size="small"
          onClick={onClose}
          sx={{ position: 'absolute', right: 12, top: 12 }}
        >
          <CloseIcon fontSize="small" />
        </IconButton>
      </DialogTitle>

      <DialogContent dividers>
        <Stack spacing={2}>
          <Box
            sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.5 }}
          >
            <KpiTile
              label="Cache efficiency"
              value={formatCacheEfficiency(row.cacheEfficiency)}
              color={bandColor}
            />
            <KpiTile label="Cost" value={USD_FORMATTER.format(row.costUsd)} />
          </Box>

          <Box>
            <Box
              sx={{
                display: 'flex',
                height: 8,
                borderRadius: radii.xs,
                overflow: 'hidden',
                bgcolor:
                  theme.custom?.progressTrack ?? theme.palette.action.hover,
              }}
            >
              {segmentTotal > 0 &&
                nonZeroSegments.map((segment) => (
                  <Box
                    key={segment.label}
                    sx={{
                      height: '100%',
                      minWidth: MINIMUM_SEGMENT_WIDTH,
                      width: `${(segment.value / segmentTotal) * 100}%`,
                      bgcolor: segment.color,
                    }}
                  />
                ))}
            </Box>
            <Stack spacing={0.9} sx={{ mt: 1.25 }}>
              {segments.map((segment) => (
                <Box
                  key={segment.label}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1,
                    fontSize: 12.5,
                  }}
                >
                  <Box
                    sx={{
                      width: 9,
                      height: 9,
                      borderRadius: '3px',
                      flexShrink: 0,
                      bgcolor: segment.color,
                    }}
                  />
                  <Box sx={{ flex: 1, color: 'text.secondary' }}>
                    {segment.label}
                  </Box>
                  <Box sx={{ fontFamily: fontFamilies.mono, fontWeight: 600 }}>
                    {formatCompact(segment.value)}
                  </Box>
                </Box>
              ))}
            </Stack>
          </Box>

          {/* Deep link, not a plain /sessions link: the Sessions page reads
              ?sessionId= and opens that row's prompt timeline on arrival. */}
          <Button
            component={RouterLink}
            to={sessionsDeepLink(row.sessionId)}
            variant="outlined"
            color="inherit"
            fullWidth
            endIcon={<ArrowForwardIcon />}
            sx={{
              height: 40,
              borderRadius: radii.sm,
              fontFamily: fontFamilies.display,
            }}
          >
            View in Sessions
          </Button>
        </Stack>
      </DialogContent>
    </>
  );
};

/**
 * One session's cache picture, opened from a row of the worst-cache-efficiency
 * ranking: its band, its cost, and the four-way split of every token the session
 * moved.
 *
 * Everything shown comes from the row the caller already has — the ranking
 * response carries all four kinds (`inputTokens` / `cacheCreationTokens` /
 * `cacheReadTokens`, summing to `inputSideTokens`, plus `outputTokens`), so
 * opening this costs no fetch.
 *
 * The bar and the cache-efficiency KPI above it are deliberately measured over
 * different denominators: the ratio excludes output (generated, never sent, so
 * the cache could not have served it) while the bar includes it, because the bar
 * answers "what did this session spend its tokens on" rather than "what could
 * have been cached". Don't read the Cache read segment's width as the KPI's
 * percentage — it is smaller by exactly the output share.
 */
const SessionCacheEfficiencyDialog = ({
  row,
  onClose,
}: SessionCacheEfficiencyDialogProps) => {
  // Keep the last selection rendered while the Dialog's exit transition runs,
  // so the body doesn't flash to an empty/degraded state during the ~200ms
  // fade-out — the same guarded render-phase pattern SpanInspectorDrawer uses
  // so its content "slides out instead of vanishing". Compared by session id,
  // not object identity: the caller can rebuild an equal row as a new object.
  const [lastRow, setLastRow] = useState<SessionCacheEfficiencyRow | null>(
    null,
  );
  if (row != null && row.sessionId !== lastRow?.sessionId) {
    setLastRow(row);
  }
  const effectiveRow = row ?? lastRow;

  return (
    <Dialog open={row != null} onClose={onClose} maxWidth="xs" fullWidth>
      {effectiveRow != null ? (
        <DialogBody row={effectiveRow} onClose={onClose} />
      ) : null}
    </Dialog>
  );
};

export default SessionCacheEfficiencyDialog;
