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
import { useQuery } from '@tanstack/react-query';
import { Tooltip, Typography } from '@mui/material';
import { fetchSystemBuild } from '../pages/SettingsPage/settingsApi';

const SNAPSHOT_SUFFIX = '-SNAPSHOT';
// Reported by the backend when META-INF/build-info.properties is absent (IDE-launched runs).
const DEVELOPMENT_VERSION = 'dev';

// The release workflow packages the jar from a bumped `-SNAPSHOT` pom, so a released image
// reports e.g. `2.7.2-SNAPSHOT`. The suffix is a build-tooling detail, not something to show.
const formatVersionLabel =(applicationVersion: string): string => {
  if (applicationVersion === DEVELOPMENT_VERSION) {
    return applicationVersion;
  }
  const releaseVersion = applicationVersion.endsWith(SNAPSHOT_SUFFIX)
    ? applicationVersion.slice(0, -SNAPSHOT_SUFFIX.length)
    : applicationVersion;
  return `v${releaseVersion}`;
};

const SidebarVersion = () => {
  // Same key as the Settings page, so the two share one cached request.
  const buildQuery = useQuery({ queryKey: ['system-build'], queryFn: fetchSystemBuild });

  if (!buildQuery.data) {
    return null;
  }

  const { applicationVersion, buildTime } = buildQuery.data;
  const tooltip = buildTime
    ? `Agent Compass ${applicationVersion} · built ${new Date(buildTime).toLocaleString()}`
    : `Agent Compass ${applicationVersion}`;

  return (
    <Tooltip title={tooltip} placement="right">
      <Typography
        variant="caption"
        noWrap
        sx={{
          display: 'block',
          pb: 1.25,
          textAlign: 'center',
          color: 'text.secondary',
          typography: 'mono',
          fontSize: 10.5,
        }}
      >
        {formatVersionLabel(applicationVersion)}
      </Typography>
    </Tooltip>
  );
};

export default SidebarVersion;
