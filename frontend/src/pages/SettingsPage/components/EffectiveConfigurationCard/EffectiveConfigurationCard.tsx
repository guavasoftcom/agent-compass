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
import { useEffect, useMemo, useRef, useState } from 'react';
import { alpha, Box, ButtonBase, Paper, Stack, Tooltip, Typography, useTheme } from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import SearchInput from '../../../../components/SearchInput';
import { fontFamilies } from '../../../../theme/typography';
import {
  filterConfigurationGroups,
  sqlMirroringExplanation,
  sqlMirroringLabel,
} from '../../settingsDerivations';
import type { ConfigurationEntry, EffectiveConfiguration, SqlMirroring } from '../../settingsTypes';

export interface EffectiveConfigurationCardProps {
  configuration: EffectiveConfiguration | null;
  isConfigurationLoading: boolean;
}

/**
 * The mirroring chip. Only the two non-default states get a chip — labelling 30
 * safe properties "safe" would bury the 17 that are not.
 */
const MirroringChip = ({ entry }: { entry: ConfigurationEntry }) => {
  const theme = useTheme();

  if (entry.sqlMirroring === 'NOT_MIRRORED') {
    return null;
  }
  const color =
    entry.sqlMirroring === 'MIRRORED' ? theme.palette.warning.main : theme.palette.info.main;

  return (
    <Tooltip arrow title={sqlMirroringExplanation(entry.sqlMirroring, entry.mirroredIn)}>
      <Box
        component="span"
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          px: 0.9,
          height: 21,
          borderRadius: '6px',
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: '0.2px',
          cursor: 'help',
          whiteSpace: 'nowrap',
          color,
          bgcolor: alpha(color, 0.15),
        }}
      >
        {sqlMirroringLabel(entry.sqlMirroring)} {entry.mirroredIn.join(' ')}
      </Box>
    </Tooltip>
  );
};

const OverriddenChip = () => {
  const theme = useTheme();
  return (
    <Tooltip arrow title="Differs from the compiled-in default — set in application.yml or the environment.">
      <Box
        component="span"
        sx={{
          px: 0.9,
          height: 21,
          display: 'inline-flex',
          alignItems: 'center',
          borderRadius: '6px',
          fontSize: 11,
          fontWeight: 700,
          cursor: 'help',
          color: theme.palette.primary.main,
          bgcolor: alpha(theme.palette.primary.main, 0.15),
        }}
      >
        overridden
      </Box>
    </Tooltip>
  );
};

/**
 * Every resolved `tuning.*` property, grouped, searchable, and flagged where
 * overriding it also requires a Flyway migration.
 *
 * That flag is the reason this card exists. Native SQL cannot read Spring
 * properties at parse time, so 17 of these values are duplicated as literals in
 * generated columns, views, the severity function, and index predicates.
 * Overriding one of them without the matching migration does not fail — the
 * affected page just quietly reads the wrong rows.
 */
/** A property's mirroring flag is what "flagged" counts in a group header. */
const isFlagged = (entry: ConfigurationEntry): boolean =>
  entry.sqlMirroring !== ('NOT_MIRRORED' as SqlMirroring);

const EffectiveConfigurationCard = ({
  configuration,
  isConfigurationLoading,
}: EffectiveConfigurationCardProps) => {
  const [query, setQuery] = useState('');
  const [openGroupNames, setOpenGroupNames] = useState<Set<string>>(new Set());
  const hasSetDefaultOpenGroup = useRef(false);

  // Only the first group starts open; this can't be a lazy initializer because
  // it depends on configuration arriving from the network.
  useEffect(() => {
    if (hasSetDefaultOpenGroup.current || !configuration || configuration.groups.length === 0) {
      return;
    }
    hasSetDefaultOpenGroup.current = true;
    setOpenGroupNames(new Set([configuration.groups[0].name]));
  }, [configuration]);

  const isSearching = query.trim().length > 0;

  const toggleGroup = (groupName: string) => {
    setOpenGroupNames((previous) => {
      const next = new Set(previous);
      if (next.has(groupName)) {
        next.delete(groupName);
      } else {
        next.add(groupName);
      }
      return next;
    });
  };

  const visibleGroups = useMemo(
    () => (configuration ? filterConfigurationGroups(configuration.groups, query) : []),
    [configuration, query],
  );

  const mirroredCount = useMemo(() => {
    if (!configuration) {
      return 0;
    }
    return configuration.groups.flatMap((group) => group.entries).filter(isFlagged).length;
  }, [configuration]);

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between' }}
      >
        <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.5 }}>
          {configuration
            ? `${configuration.propertyCount} properties · ${configuration.overriddenCount} overridden · ${mirroredCount} duplicated in migration SQL`
            : 'Resolving…'}
        </Typography>
        <SearchInput
          value={query}
          onChange={setQuery}
          placeholder="Filter properties"
          sx={{ minWidth: { sm: 240 } }}
        />
      </Stack>

      <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5, mb: 2, lineHeight: 1.5 }}>
        Native SQL cannot read Spring properties, so some of these values are written as literals
        into generated columns, views, the severity function, and index predicates. Overriding a
        flagged property without a matching migration does not error — the affected page silently
        reads the wrong rows.
      </Typography>

      {!configuration && isConfigurationLoading ? (
        <Typography color="text.secondary">Resolving…</Typography>
      ) : visibleGroups.length === 0 ? (
        <Typography color="text.secondary">No properties match this filter.</Typography>
      ) : (
        <Stack spacing={1.5}>
          {visibleGroups.map((group) => {
            const isOpen = isSearching || openGroupNames.has(group.name);
            const flaggedCount = group.entries.filter(isFlagged).length;
            return (
              <Box
                key={group.name}
                sx={{ border: 1, borderColor: 'divider', borderRadius: 1.75, overflow: 'hidden' }}
              >
                <ButtonBase
                  onClick={() => toggleGroup(group.name)}
                  aria-expanded={isOpen}
                  sx={{
                    width: '100%',
                    display: 'flex',
                    alignItems: 'flex-start',
                    justifyContent: 'space-between',
                    gap: 2,
                    p: '13px 16px',
                    textAlign: 'left',
                    '&:hover': { bgcolor: 'action.hover' },
                  }}
                >
                  <Box>
                    <Typography
                      sx={{ fontFamily: fontFamilies.display, fontWeight: 700, fontSize: 15 }}
                    >
                      {group.name}
                    </Typography>
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ display: 'block', mt: 0.35, lineHeight: 1.5 }}
                    >
                      {group.description}
                    </Typography>
                  </Box>
                  <Stack
                    direction="row"
                    spacing={1.25}
                    sx={{ alignItems: 'center', flexShrink: 0, mt: 0.4 }}
                  >
                    <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
                      {group.entries.length} {group.entries.length === 1 ? 'property' : 'properties'}
                      {flaggedCount > 0 && (
                        <>
                          {' · '}
                          <Box component="b" sx={{ color: 'text.primary' }}>
                            {flaggedCount}
                          </Box>{' '}
                          flagged
                        </>
                      )}
                    </Typography>
                    <ExpandMoreIcon
                      fontSize="small"
                      sx={{
                        color: 'text.secondary',
                        transition: 'transform 200ms ease',
                        transform: isOpen ? 'rotate(180deg)' : 'none',
                      }}
                    />
                  </Stack>
                </ButtonBase>

                {isOpen && (
                  <Stack spacing={0} sx={{ px: 2, pb: 0.5 }}>
                    {group.entries.map((entry) => (
                      <Box
                        key={entry.propertyName}
                        sx={{
                          py: 1.1,
                          borderTop: 1,
                          borderColor: 'divider',
                          display: 'grid',
                          gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1.1fr) minmax(0, 1fr)' },
                          gap: { xs: 0.5, md: 2 },
                          alignItems: 'baseline',
                        }}
                      >
                        <Box sx={{ minWidth: 0 }}>
                          <Stack
                            direction="row"
                            spacing={0.75}
                            sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}
                          >
                            <Box component="span" sx={{ typography: 'mono', fontSize: 13 }}>
                              {entry.propertyName}
                            </Box>
                            {entry.overridden && <OverriddenChip />}
                            <MirroringChip entry={entry} />
                          </Stack>
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{ display: 'block', mt: 0.35, lineHeight: 1.45 }}
                          >
                            {entry.description}
                          </Typography>
                        </Box>
                        <Box
                          sx={{
                            typography: 'mono',
                            fontSize: 12.5,
                            color: 'text.primary',
                            wordBreak: 'break-word',
                          }}
                        >
                          {entry.value}
                        </Box>
                      </Box>
                    ))}
                  </Stack>
                )}
              </Box>
            );
          })}
        </Stack>
      )}
    </Paper>
  );
};

export default EffectiveConfigurationCard;
