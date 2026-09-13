/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
import { alpha, Chip, Paper, Skeleton, Stack, Typography, useTheme } from '@mui/material';
import GhostButton from '../../../../components/GhostButton';
import { PRIMARY_ACTION_BUTTON_SX } from '../OllamaConfigurationCard';
import type { OllamaConnectionTestResult, OllamaSettings } from '../../settingsTypes';

export interface OllamaStatusCardProps {
  ollamaSettings: OllamaSettings | null;
  isOllamaSettingsLoading: boolean;
  enabled: boolean;
  isTestingConnection: boolean;
  testConnectionResult: OllamaConnectionTestResult | null;
  testConnectionError: Error | null;
  onTestConnection: () => void;
}

/**
 * The Ollama tab's own card, stacked below `OllamaConfigurationCard` (both full-width, per the
 * design handoff — unlike the Storage & Ingest tab's side-by-side `StorageBreakdownCard` +
 * `DonutCard`, this pair reads top-to-bottom) — split out so the reachability probe and its
 * result don't visually compete with the editable form. Carries the Overridden/Using default chip
 * that used to sit on the configuration card's own header, since "is this a stored override" reads
 * more naturally next to "is it currently reachable" than next to the fields being edited.
 *
 * Pure props in / JSX out, same as every other card on this page — the probe result, the
 * in-flight flag, and the reachability chip all live in the container (`SettingsPage.tsx`). Shows
 * "Not tested since load." until the operator runs a probe (or an in-flight/error/result state
 * takes over), so the card never renders as an empty box before the first test.
 *
 * "Test connection" probes Ollama with whatever is currently typed in the sibling card's fields,
 * independent of whether it has been saved — the response is a normal success/failure result, not
 * an HTTP error, so a currently-unreachable Ollama renders as a readable message rather than a
 * query error state. Disabled together with the configuration card's own fields/Save whenever
 * `enabled` is false, matching the design handoff's "Off state dims and disables ... the Test
 * connection button" — client-side only; `TraceAnalysisService.regenerate` re-checks the
 * effective flag server-side before ever calling Ollama for a real analysis.
 */
const OllamaStatusCard = ({
  ollamaSettings,
  isOllamaSettingsLoading,
  enabled,
  isTestingConnection,
  testConnectionResult,
  testConnectionError,
  onTestConnection,
}: OllamaStatusCardProps) => {
  const theme = useTheme();

  if (isOllamaSettingsLoading && !ollamaSettings) {
    return (
      <Paper variant="outlined" sx={{ p: '22px 24px' }}>
        <Skeleton variant="text" width="50%" sx={{ mb: 2 }} />
        <Skeleton variant="rounded" height={36} />
      </Paper>
    );
  }

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Stack
        direction="row"
        spacing={1.5}
        sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1 }}
      >
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Status
        </Typography>
        {ollamaSettings?.overridden ? (
          <Chip label="Overridden" color="info" size="small" variant="outlined" />
        ) : (
          <Chip label="Using default" size="small" variant="outlined" />
        )}
      </Stack>

      <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.5, mb: 2 }}>
        Probes Ollama with whatever is currently in the fields, saved or not.
      </Typography>

      <GhostButton
        onClick={onTestConnection}
        disabled={isTestingConnection || !enabled}
        sx={PRIMARY_ACTION_BUTTON_SX}
      >
        {isTestingConnection ? 'Testing…' : 'Test connection'}
      </GhostButton>

      {testConnectionError && (
        <Typography variant="body2" sx={{ mt: 1.5, color: 'error.main' }}>
          {testConnectionError.message}
        </Typography>
      )}

      {testConnectionResult && (
        <Typography
          variant="body2"
          sx={{
            mt: 1.5,
            p: 1.25,
            borderRadius: 1.5,
            border: 1,
            borderColor: alpha(
              testConnectionResult.success
                ? theme.palette.success.main
                : theme.palette.error.main,
              0.35,
            ),
            bgcolor: alpha(
              testConnectionResult.success
                ? theme.palette.success.main
                : theme.palette.error.main,
              0.08,
            ),
            color: testConnectionResult.success ? 'success.main' : 'error.main',
          }}
        >
          {testConnectionResult.message}
        </Typography>
      )}

      {!testConnectionResult && !testConnectionError && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
          Not tested since load.
        </Typography>
      )}
    </Paper>
  );
};

export default OllamaStatusCard;
