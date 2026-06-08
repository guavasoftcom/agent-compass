import {
  Box,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import type { ToolRepeatStatRow } from '../../../../api';

export interface ToolRepeatsCardProps {
  rows: ToolRepeatStatRow[];
  isLoading: boolean;
}

const NO_SCOPE_LABEL = '(no scope)';

// Tint the "max run" value by how long the chain is: longer chains are more of a smell.
const runTone = (value: number): { bg: string; fg: string } => {
  if (value >= 6) {
    return { bg: 'color-mix(in srgb, #e5484d 16%, transparent)', fg: '#e5484d' };
  }
  if (value >= 4) {
    return { bg: 'color-mix(in srgb, #e6952b 18%, transparent)', fg: '#e6952b' };
  }
  return { bg: 'rgba(124,77,255,0.12)', fg: 'inherit' };
};

const headSx = {
  fontFamily: "'Sora', sans-serif",
  fontSize: 11,
  fontWeight: 700,
  letterSpacing: 0.6,
  textTransform: 'uppercase',
  color: 'text.secondary',
  borderColor: 'divider',
} as const;

const ToolRepeatsCard = ({ rows, isLoading }: ToolRepeatsCardProps) => {
  const hasData = rows.length > 0;
  return (
    <Paper variant="outlined" sx={{ p: 2.5 }}>
      <Typography variant="subtitle1" gutterBottom>
        Same-tool repeats per session
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1, maxWidth: 760 }}>
        Longest consecutive run of the same tool acting on the same scope within a session,
        rolled up across sessions. Long chains on the same file are a sign the agent is hunting —
        AGENTS.md can encourage reading once, planning all changes, then writing in a single pass.
      </Typography>
      {!hasData && !isLoading ? (
        <Typography color="text.secondary">No repeating tool runs in this window.</Typography>
      ) : (
        <Table
          size="small"
          sx={{
            '& td, & th': { borderColor: 'divider' },
            '& tbody tr:last-of-type td': { border: 0 },
            '& tbody tr': { transition: 'background-color 120ms' },
            '& tbody tr:hover': { backgroundColor: 'action.hover' },
          }}
        >
          <TableHead>
            <TableRow>
              <TableCell sx={headSx}>Tool</TableCell>
              <TableCell sx={headSx}>Scope</TableCell>
              <TableCell align="right" sx={headSx}>Median run</TableCell>
              <TableCell align="right" sx={headSx}>Max run</TableCell>
              <TableCell align="right" sx={headSx}>Sessions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => {
              const isUnscoped = row.scope === NO_SCOPE_LABEL || row.scope === '';
              const tone = runTone(row.maxRunLength);
              return (
                <TableRow key={`${row.tool}::${row.scope}`}>
                  <TableCell sx={{ fontWeight: 600 }}>{row.tool}</TableCell>
                  <TableCell
                    sx={{
                      maxWidth: 360,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                      fontFamily: isUnscoped ? undefined : 'monospace',
                      color: isUnscoped ? 'text.disabled' : 'text.primary',
                      fontStyle: isUnscoped ? 'italic' : 'normal',
                    }}
                  >
                    {isUnscoped ? (
                      NO_SCOPE_LABEL
                    ) : (
                      <Tooltip
                        title={
                          <Typography
                            variant="caption"
                            sx={{
                              fontFamily: 'monospace',
                              whiteSpace: 'pre-wrap',
                              wordBreak: 'break-all',
                            }}
                          >
                            {row.scope}
                          </Typography>
                        }
                        placement="top-start"
                        arrow
                      >
                        <span>{row.scope}</span>
                      </Tooltip>
                    )}
                  </TableCell>
                  <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 700 }}>
                    {row.medianRunLength.toLocaleString()}
                  </TableCell>
                  <TableCell align="right">
                    <Box
                      component="span"
                      sx={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        minWidth: 26,
                        height: 24,
                        px: 1,
                        borderRadius: '7px',
                        fontWeight: 700,
                        fontSize: 13,
                        fontVariantNumeric: 'tabular-nums',
                        bgcolor: tone.bg,
                        color: tone.fg,
                      }}
                    >
                      {row.maxRunLength.toLocaleString()}
                    </Box>
                  </TableCell>
                  <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums', color: 'text.secondary' }}>
                    {row.sessions.toLocaleString()}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
    </Paper>
  );
};

export default ToolRepeatsCard;
