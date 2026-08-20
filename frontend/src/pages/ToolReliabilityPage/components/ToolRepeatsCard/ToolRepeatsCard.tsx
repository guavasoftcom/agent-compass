import {
  alpha,
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
import { auroraColors, severity } from '../../../../theme/colors';
import type { ToolRepeatStatRow } from '../../../../api';

export interface ToolRepeatsCardProps {
  rows: ToolRepeatStatRow[];
  isLoading: boolean;
}

// Real sandbox scratch paths look like /private/tmp/claude-501/-Users-name-Projects-repo/foo —
// the mangled project-dir segment carries no information, so it collapses to a fixed label.
const SANDBOX_TMP_PATTERN = /^\/private\/tmp\/(claude-\d+)\/[^/]+\/(.+)$/;

interface ScopeDisplay {
  kind: 'file' | 'sandbox' | 'command';
  primary: string;
  secondary?: string;
}

const describeScope = (tool: string, scope: string): ScopeDisplay => {
  const isFileTool =
    tool === 'Edit' ||
    tool === 'Write' ||
    tool === 'Read' ||
    tool === 'MultiEdit';

  if (isFileTool) {
    const sandboxMatch = scope.match(SANDBOX_TMP_PATTERN);
    if (sandboxMatch) {
      const basename = sandboxMatch[2].split('/').pop() ?? sandboxMatch[2];
      return {
        kind: 'sandbox',
        primary: basename,
        secondary: `sandbox tmp · ${sandboxMatch[1]}/`,
      };
    }
    const lastSlash = scope.lastIndexOf('/');
    if (lastSlash === -1) {
      return { kind: 'file', primary: scope };
    }
    return {
      kind: 'file',
      primary: scope.slice(lastSlash + 1),
      secondary: scope.slice(0, lastSlash + 1),
    };
  }

  // Bash scope is normally "program subcommand" (e.g. "git diff"), but a bare, un-chained
  // `cd <path>` survives the backend's cd-chain stripping — collapse that path the same way.
  const cdMatch = scope.match(/^cd\s+(\S+)$/);
  if (cdMatch) {
    const sandboxMatch = cdMatch[1].match(SANDBOX_TMP_PATTERN);
    if (sandboxMatch) {
      const basename = sandboxMatch[2].split('/').pop() ?? sandboxMatch[2];
      return {
        kind: 'sandbox',
        primary: `cd ${basename}`,
        secondary: `sandbox tmp · ${sandboxMatch[1]}/`,
      };
    }
  }
  return { kind: 'command', primary: scope };
};

// Tint the "max run" value by how long the chain is: longer chains are more of a smell.
// Thresholds are tuned to the real value range (clustering 4-14), not the 3-7 range a small
// curated demo dataset happened to have.
const runTone = (value: number): { bg: string; fg: string } => {
  if (value >= 10) {
    return { bg: alpha(severity.severe, 0.16), fg: severity.severe };
  }
  if (value >= 6) {
    return { bg: alpha(severity.warning, 0.18), fg: severity.warning };
  }
  return { bg: alpha(auroraColors.violet, 0.12), fg: auroraColors.violet };
};

const headSx = {
  typography: 'eyebrowSm',
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
      <Typography
        variant="body2"
        color="text.secondary"
        sx={{ mb: 0.5, maxWidth: 760 }}
      >
        Longest consecutive run of the same tool acting on the same scope within
        a session, rolled up across sessions. Long chains on the same file are a
        sign the agent is hunting — AGENTS.md can encourage reading once,
        planning all changes, then writing in a single pass.
      </Typography>
      {hasData && (
        <Typography
          variant="caption"
          color="text.disabled"
          sx={{ display: 'block', mb: 1, mt: 1.5 }}
        >
          Hover a scope for its full path ·{' '}
          <Box
            component="span"
            sx={{
              color: severity.warning,
              fontStyle: 'italic',
              textTransform: 'uppercase',
              fontSize: 10,
              letterSpacing: 0.4,
            }}
          >
            spike
          </Box>{' '}
          = ran in only one session, may be a one-off
        </Typography>
      )}
      {!hasData && !isLoading ? (
        <Typography color="text.secondary">
          No repeating tool runs in this window.
        </Typography>
      ) : (
        <Table
          size="small"
          sx={{
            tableLayout: 'fixed',
            '& td, & th': { borderColor: 'divider' },
            '& tbody tr:last-of-type td': { border: 0 },
            '& tbody tr': { transition: 'background-color 120ms' },
            '& tbody tr:hover': { backgroundColor: 'action.hover' },
          }}
        >
          <colgroup>
            <col style={{ width: '10%' }} />
            <col style={{ width: '46%' }} />
            <col style={{ width: '14%' }} />
            <col style={{ width: '16%' }} />
            <col style={{ width: '14%' }} />
          </colgroup>
          <TableHead>
            <TableRow>
              <TableCell sx={headSx}>Tool</TableCell>
              <TableCell sx={headSx}>Scope</TableCell>
              <TableCell align="right" sx={headSx}>
                Median run
              </TableCell>
              <TableCell align="right" sx={headSx}>
                Max run
              </TableCell>
              <TableCell align="right" sx={headSx}>
                Sessions
              </TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => {
              const tone = runTone(row.maxRunLength);
              const scope = describeScope(row.tool, row.scope);
              return (
                <TableRow key={`${row.tool}::${row.scope}`}>
                  <TableCell sx={{ fontWeight: 600 }}>{row.tool}</TableCell>
                  <TableCell sx={{ minWidth: 0 }}>
                    {scope.kind === 'command' ? (
                      <Typography
                        noWrap
                        sx={{ fontFamily: 'monospace', fontSize: 13.5 }}
                      >
                        <Box
                          component="span"
                          sx={{
                            color: 'text.disabled',
                            fontWeight: 600,
                            mr: 0.75,
                          }}
                        >
                          $
                        </Box>
                        {scope.primary}
                      </Typography>
                    ) : (
                      <Tooltip
                        title={
                          <Typography
                            variant="caption"
                            sx={{
                              typography: 'mono',
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
                        <Box sx={{ minWidth: 0 }}>
                          <Typography
                            noWrap
                            sx={{
                              fontFamily: 'monospace',
                              fontSize: 13.5,
                              fontWeight: 600,
                            }}
                          >
                            {scope.primary}
                          </Typography>
                          {scope.secondary && (
                            <Typography
                              noWrap
                              sx={{
                                fontFamily: 'monospace',
                                fontSize: 11.5,
                                color: 'text.disabled',
                                fontStyle:
                                  scope.kind === 'sandbox'
                                    ? 'italic'
                                    : 'normal',
                                mt: 0.25,
                              }}
                            >
                              {scope.secondary}
                            </Typography>
                          )}
                        </Box>
                      </Tooltip>
                    )}
                  </TableCell>
                  <TableCell
                    align="right"
                    sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 700 }}
                  >
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
                  <TableCell
                    align="right"
                    sx={{
                      fontVariantNumeric: 'tabular-nums',
                      color: 'text.secondary',
                    }}
                  >
                    {row.sessions.toLocaleString()}
                    {row.sessions === 1 && (
                      <Box
                        component="span"
                        sx={{
                          ml: 0.75,
                          fontSize: 10,
                          letterSpacing: 0.4,
                          textTransform: 'uppercase',
                          fontStyle: 'italic',
                          color: severity.warning,
                        }}
                      >
                        spike
                      </Box>
                    )}
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
