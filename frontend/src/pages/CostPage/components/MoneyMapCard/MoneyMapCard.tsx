import { Box, Paper, Tooltip, Typography } from '@mui/material';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import BreakdownList from '../../../../components/BreakdownList';
import type { CostCategoryShare } from '../../../../api';
import { USD_FORMATTER } from '../../../../lib/format';
import { CATEGORY_LABELS, categoryColorIndex } from '../../costDerivations';

export interface MoneyMapCardProps {
  categories: CostCategoryShare[];
  isLoading: boolean;
}

/**
 * The Cost page's centerpiece: every `api_request` row in the window partitioned into
 * exactly one of four work categories (Main loop / Subagents / Skills / Auxiliary), so
 * the bars sum to the page total by construction — see `CostCategoryShare`'s backend
 * doc for the precedence rule that makes that true even for a skill running inside a
 * subagent.
 *
 * Renders only the top-level 4-category breakdown. The per-identifier subagent/skill
 * drilldown that used to render nested under this card was removed (2026-08 Aurora
 * handoff) — it duplicated the "Skill mix" / "Subagent mix" donut+ranked-list cards
 * elsewhere on the "Where it went" tab, which are now the single source for that data.
 * `categories[].drilldown` / `identifiedCostUsd` still arrive on the wire; this card
 * just no longer reads them.
 */
const MoneyMapCard = ({ categories, isLoading }: MoneyMapCardProps) => {
  const rows = categories.map((category) => ({
    label: CATEGORY_LABELS[category.category],
    value: USD_FORMATTER.format(category.costUsd),
    percentage: category.share,
    colorIndex: categoryColorIndex(category.category),
  }));

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mb: 0.5 }}>
        <Typography variant="subtitle1">Where the money went</Typography>
        <Tooltip
          title={
            'Each request counts toward exactly one category, so the bars always add up to the '
            + 'total above. A request that could fit more than one category (e.g. a skill running '
            + 'inside a subagent) is counted as Subagent. Based on each request\'s exact cost, not '
            + 'the running counter used on the Tokens and Sessions pages.'
          }
          arrow
        >
          <InfoOutlinedIcon sx={{ fontSize: 18, color: 'text.secondary', cursor: 'help' }} />
        </Tooltip>
      </Box>

      {!isLoading && categories.length === 0 ? (
        <Typography color="text.secondary">No priced requests in this window.</Typography>
      ) : (
        <BreakdownList rows={rows} layout="stacked" showColorDot />
      )}
    </Paper>
  );
};

export default MoneyMapCard;
