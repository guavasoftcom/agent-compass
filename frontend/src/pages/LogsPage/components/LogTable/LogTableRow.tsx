import { Box } from '@mui/material';
import type { LogRow } from '../../../../api';
import { severityOf } from '../../logsApi';
import { SeverityChip } from '../SeverityChip';
import { AttributeValue, type ValueDialogState } from '../../../../components/AttributeList/AttributeValue';
import { fontFamilies } from '../../../../theme/typography';

interface LogTableRowProps {
  row: LogRow;
  /** Whether this row's attributes are expanded past the first 4. */
  expanded: boolean;
  onToggle: () => void;
  /** Open the shared "view more" repair dialog for a long attribute value. */
  onExpandValue: (state: ValueDialogState) => void;
}

// One table row: id, timestamp, severity, body, scope, and the attributes cell
// (first 4 chips + a "+N more" toggle). Rendered by LogTable's tbody.
const LogTableRow = ({ row, expanded, onToggle, onExpandValue }: LogTableRowProps) => {
  const attrs = row.attributes ?? {};
  const keys = Object.keys(attrs);
  const shown = expanded ? keys : keys.slice(0, 4);

  return (
    <Box component="tr">
      <Box component="td" className="id">
        {row.id}
      </Box>
      <Box component="td" className="ts">
        {new Date(row.timestamp).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })}
      </Box>
      <Box component="td">
        <SeverityChip severity={severityOf(row)} />
      </Box>
      <Box component="td" className="body">
        {row.body}
      </Box>
      <Box component="td" className="scope">
        {row.scopeName}
      </Box>
      <Box component="td" className="attr">
        {shown.map((k) => (
          <Box
            key={k}
            component="span"
            sx={{ display: 'inline-flex', flexWrap: 'wrap', alignItems: 'baseline', columnGap: 0.4, mr: 0.75, mb: 0.6, fontFamily: fontFamilies.mono, fontSize: 11, bgcolor: 'action.hover', borderRadius: 0.75, px: 0.9, py: 0.25, color: 'text.secondary', maxWidth: '100%', overflowWrap: 'anywhere' }}
          >
            {`${k}=`}
            <Box component="span" sx={{ color: 'text.primary', fontWeight: 600 }}>
              <AttributeValue attrKey={k} value={attrs[k]} truncate onExpand={onExpandValue} />
            </Box>
          </Box>
        ))}
        {keys.length > 4 ? (
          <Box
            component="button"
            type="button"
            onClick={onToggle}
            sx={{ border: 'none', background: 'none', p: 0, cursor: 'pointer', fontFamily: fontFamilies.body, color: 'primary.main', fontWeight: 600, fontSize: 12, '&:hover': { textDecoration: 'underline' } }}
          >
            {expanded ? 'show less' : `+${keys.length - 4} more`}
          </Box>
        ) : null}
      </Box>
    </Box>
  );
};

export default LogTableRow;
