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
import type { ReactNode } from 'react';
import { alpha, Box, Paper, Tooltip, Typography } from '@mui/material';
import { formatRelativeTime, formatTimestamp } from '../../../lib/format';
import type { SystemBuild } from '../settingsTypes';

export interface SchemaBuildCardProps {
  systemBuild: SystemBuild | null;
  isBuildLoading: boolean;
}

const numberCell = { textAlign: 'right' as const, fontVariantNumeric: 'tabular-nums' };

// Hand-built to match the Aurora table pattern used by SessionsTable/TraceTableView
// (sticky eyebrowSm uppercase headers, hairline dividers, zebra rows, soft hover) —
// the plain MUI Table this replaced had none of that theming.
const TableHeaderCell = ({
  children,
  align,
}: {
  children?: ReactNode;
  align?: 'right';
}) => (
  <Box
    component="th"
    sx={{
      typography: 'eyebrowSm',
      position: 'sticky',
      top: 0,
      zIndex: 2,
      color: 'text.secondary',
      textAlign: align ?? 'left',
      px: 1.75,
      py: 1.5,
      borderBottom: 1,
      borderColor: 'divider',
      whiteSpace: 'nowrap',
      bgcolor: 'background.paper',
    }}
  >
    {children}
  </Box>
);

const cellSx = {
  px: 1.75,
  py: 1.4,
  fontSize: 13,
  borderBottom: 1,
  borderColor: 'divider',
  verticalAlign: 'middle' as const,
};

/** One label/value pair in the version strip above the migration table. */
const VersionFact = ({ label, value, hint }: { label: string; value: string; hint?: string }) => (
  <Box>
    <Typography
      variant="caption"
      sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: 0.6, fontSize: 10.5 }}
    >
      {label}
    </Typography>
    <Tooltip arrow title={hint ?? ''} disableHoverListener={!hint}>
      <Typography
        sx={{ typography: 'mono', fontSize: 13.5, mt: 0.25, cursor: hint ? 'help' : 'default' }}
      >
        {value}
      </Typography>
    </Tooltip>
  </Box>
);

/**
 * Running versions and the schema's migration history.
 *
 * Hibernate runs with `ddl-auto=validate`, so this table is the complete account
 * of how the database reached its current shape — nothing else can have altered
 * it. A row that failed would have aborted startup, so every row here reading
 * "applied" is the expected state, not a coincidence.
 */
const SchemaBuildCard = ({ systemBuild, isBuildLoading }: SchemaBuildCardProps) => {
  if (!systemBuild) {
    return (
      <Paper variant="outlined" sx={{ p: '22px 24px' }}>
        <Typography color="text.secondary">
          {isBuildLoading ? 'Reading…' : 'No build information reported.'}
        </Typography>
      </Paper>
    );
  }

  const latestMigration = systemBuild.migrations[0];

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2, lineHeight: 1.5 }}>
        Hibernate validates the entity model against this schema at startup, so the migration list
        below is the whole story of how it got here.
      </Typography>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: 'repeat(2, 1fr)', md: 'repeat(4, 1fr)' },
          gap: 2,
          mb: 2.5,
        }}
      >
        <VersionFact
          label="Application"
          value={systemBuild.applicationVersion.replace(/-SNAPSHOT$/, '')}
          hint={
            systemBuild.buildTime
              ? `Built ${formatTimestamp(systemBuild.buildTime)}`
              : 'No build metadata — this process was launched without a packaging step.'
          }
        />
        <VersionFact
          label="Java"
          value={systemBuild.javaVersion}
          hint={`${systemBuild.jvmName} · ${systemBuild.javaVendor}`}
        />
        <VersionFact label="PostgreSQL" value={systemBuild.postgresVersion} />
        <VersionFact
          label="Schema"
          value={latestMigration?.version ? `V${latestMigration.version}` : '—'}
          hint={
            latestMigration
              ? `${latestMigration.description} · applied ${formatRelativeTime(latestMigration.installedOn)}`
              : undefined
          }
        />
      </Box>

      <Box sx={{ overflowX: 'auto', maxHeight: 340, overflowY: 'auto' }}>
        <Box component="table" sx={{ width: '100%', borderCollapse: 'collapse', minWidth: 640 }}>
          <Box component="thead">
            <Box component="tr">
              <TableHeaderCell>Version</TableHeaderCell>
              <TableHeaderCell>Description</TableHeaderCell>
              <TableHeaderCell>Script</TableHeaderCell>
              <TableHeaderCell align="right">Took</TableHeaderCell>
              <TableHeaderCell align="right">Applied</TableHeaderCell>
            </Box>
          </Box>
          <Box component="tbody">
            {systemBuild.migrations.map((migration, index) => (
              <Box
                key={migration.installedRank}
                component="tr"
                sx={{
                  '& > td': {
                    bgcolor: index % 2 ? (t) => alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.04 : 0.022) : 'transparent',
                  },
                  '&:hover > td': { bgcolor: 'action.hover' },
                }}
              >
                <Box component="td" sx={{ ...cellSx, typography: 'mono', fontWeight: 700 }}>
                  {migration.version ?? migration.type}
                </Box>
                <Box component="td" sx={cellSx}>
                  {migration.description}
                </Box>
                <Box
                  component="td"
                  sx={{
                    ...cellSx,
                    typography: 'mono',
                    color: 'text.secondary',
                    maxWidth: 320,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {migration.script}
                </Box>
                <Box component="td" sx={{ ...cellSx, ...numberCell }}>
                  {`${migration.executionTimeMillis.toLocaleString()} ms`}
                </Box>
                <Box component="td" sx={{ ...cellSx, ...numberCell, color: 'text.secondary' }}>
                  <Tooltip arrow title={formatTimestamp(migration.installedOn)}>
                    <Box component="span" sx={{ cursor: 'help' }}>
                      {formatRelativeTime(migration.installedOn)}
                    </Box>
                  </Tooltip>
                </Box>
              </Box>
            ))}
          </Box>
        </Box>
      </Box>
    </Paper>
  );
};

export default SchemaBuildCard;
