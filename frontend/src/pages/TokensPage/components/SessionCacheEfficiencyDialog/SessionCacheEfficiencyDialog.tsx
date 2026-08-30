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
import KpiTile from '../../../../components/KpiTile';
import SegmentedBar from '../../../../components/SegmentedBar';
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

          <SegmentedBar
            segments={segments}
            formatValue={formatCompact}
            trackColor={theme.custom?.progressTrack ?? theme.palette.action.hover}
          />

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
