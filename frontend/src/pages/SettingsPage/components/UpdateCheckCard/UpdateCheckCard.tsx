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
import {
  Alert,
  FormControlLabel,
  Link,
  Paper,
  Skeleton,
  Stack,
  Switch,
  Typography,
} from '@mui/material';
import GhostButton from '../../../../components/GhostButton';
import { formatRelativeTime } from '../../../../lib/format';
import { PRIMARY_ACTION_BUTTON_SX } from '../OllamaConfigurationCard';
import type { UpdateCheckStatus } from '../../settingsTypes';

export interface UpdateCheckCardProps {
  updateCheck: UpdateCheckStatus | null;
  isUpdateCheckLoading: boolean;
  isCheckingForUpdate: boolean;
  isSavingEnabled: boolean;
  onEnabledChange: (enabled: boolean) => void;
  onCheckNow: () => void;
  error: Error | null;
}

const DEVELOPMENT_VERSION = 'dev';

const formatVersion = (version: string): string =>
  version === DEVELOPMENT_VERSION ? version : `v${version}`;

// The release URL is text the server relayed from a third party, so only an https link is
// rendered as one; anything else falls back to no link rather than a clickable surprise.
const isHttpsUrl = (url: string | null): url is string => url !== null && url.startsWith('https://');

/**
 * Whether a newer release than the running one exists, plus the switch that turns the check off.
 *
 * The only request this application makes to a third party is the one behind this card, which is
 * why the switch is on the card and says so. It is a real, persisted setting
 * (`update_check_settings`) enforced server-side: with it off `GET /api/system/update-check`
 * makes no outbound request at all, so this dimming is a convenience, not the guarantee.
 *
 * Pure props in / JSX out like every other card here. The switch saves the moment it is flipped
 * (one boolean, so no form and no Save button), and "Check now" is the only path that bypasses the
 * server's cache — a page load never does.
 */
const UpdateCheckCard = ({
  updateCheck,
  isUpdateCheckLoading,
  isCheckingForUpdate,
  isSavingEnabled,
  onEnabledChange,
  onCheckNow,
  error,
}: UpdateCheckCardProps) => {
  if (isUpdateCheckLoading && !updateCheck) {
    return (
      <Paper variant="outlined" sx={{ p: '22px 24px' }}>
        <Skeleton variant="text" width="40%" sx={{ mb: 2 }} />
        <Skeleton variant="rounded" height={40} />
      </Paper>
    );
  }

  const enabled = updateCheck?.enabled ?? false;

  const renderStatus = () => {
    if (!updateCheck) {
      return (
        <Typography variant="body2" color="text.secondary">
          Update status is unavailable.
        </Typography>
      );
    }
    if (!updateCheck.enabled) {
      return (
        <Typography variant="body2" color="text.secondary">
          Update checks are off. Nothing is sent to GitHub.
        </Typography>
      );
    }
    if (updateCheck.message) {
      return (
        <Typography variant="body2" color="text.secondary">
          {updateCheck.message}
        </Typography>
      );
    }
    if (updateCheck.updateAvailable && updateCheck.latestVersion) {
      return (
        <Alert
          severity="info"
          action={
            isHttpsUrl(updateCheck.releaseUrl) ? (
              <Link
                href={updateCheck.releaseUrl}
                target="_blank"
                rel="noopener noreferrer"
                underline="hover"
                sx={{ fontWeight: 600, fontSize: 13, alignSelf: 'center' }}
              >
                Release notes
              </Link>
            ) : undefined
          }
        >
          {formatVersion(updateCheck.latestVersion)} is available — you are running{' '}
          {formatVersion(updateCheck.currentVersion)}.
        </Alert>
      );
    }
    return (
      <Alert severity="success">
        You are running the latest release ({formatVersion(updateCheck.currentVersion)}).
      </Alert>
    );
  };

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between', mb: 1 }}
      >
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Updates
        </Typography>
        <FormControlLabel
          labelPlacement="start"
          control={
            <Switch
              checked={enabled}
              disabled={!updateCheck || isSavingEnabled}
              onChange={(event) => onEnabledChange(event.target.checked)}
              slotProps={{ input: { 'aria-label': 'Check for updates' } }}
            />
          }
          label={enabled ? 'Enabled' : 'Disabled'}
          sx={{ ml: 0 }}
        />
      </Stack>

      <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.5, mb: 2 }}>
        Asks GitHub whether a release newer than this one has been published. It is the only request
        Agent Compass makes to a third party: a single unauthenticated request for the latest
        release, with nothing about this install in it. Turn it off and nothing is sent.
      </Typography>

      {renderStatus()}

      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mt: 2 }}>
        <GhostButton
          onClick={onCheckNow}
          disabled={!enabled || isCheckingForUpdate || isSavingEnabled}
          sx={PRIMARY_ACTION_BUTTON_SX}
        >
          {isCheckingForUpdate ? 'Checking…' : 'Check now'}
        </GhostButton>
        {enabled && updateCheck?.checkedAt && (
          <Typography variant="caption" color="text.secondary">
            Last checked {formatRelativeTime(updateCheck.checkedAt)}
          </Typography>
        )}
      </Stack>

      {error && (
        <Typography variant="body2" sx={{ mt: 1.5, color: 'error.main' }}>
          {error.message}
        </Typography>
      )}
    </Paper>
  );
};

export default UpdateCheckCard;
