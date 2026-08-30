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
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, Typography, alpha, useTheme } from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import CheckIcon from '@mui/icons-material/Check';
import TimelineIcon from '@mui/icons-material/Timeline';
import type { LogRow } from '../../../../api';
import { AttributeList } from '../../../../components/AttributeList';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';

const detailHeadingSx = {
  typography: 'eyebrowSm' as const,
  color: 'text.secondary',
  mb: 1.1,
};

// Full-precision timestamp for the expanded row (date + year + ms) — the collapsed
// row only shows relative time, with the absolute value in a hover title.
const fullTimestamp = (iso: string): string => {
  const date = new Date(iso);
  const baseLabel = date.toLocaleString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
  return `${baseLabel}.${String(date.getMilliseconds()).padStart(3, '0')}`;
};

const MetaItem = ({ label, value }: { label: string; value: string }) => (
  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
    <Box
      component="span"
      sx={{
        typography: 'eyebrowSm',
        color: 'text.disabled',
      }}
    >
      {label}
    </Box>
    <Box
      component="span"
      sx={{
        typography: 'mono',
        fontSize: 12,
        color: 'text.primary',
      }}
    >
      {value}
    </Box>
  </Box>
);

// Expanded detail panel for a stream row: body, metadata, "Open in trace" + Copy
// JSON actions, and the attribute list. Rendered by LogStream when a row is expanded.
const LogRowDetail = ({ row }: { row: LogRow }) => {
  const theme = useTheme();
  const [copied, setCopied] = useState(false);
  const attributes = row.attributes ?? {};
  const entries = Object.entries(attributes);
  const panelBackground = alpha(
    theme.palette.text.primary,
    theme.palette.mode === 'dark' ? 0.04 : 0.035,
  );
  // Copy the full in-memory LogRow to the clipboard — no backend round-trip.
  const handleCopyJson = () => {
    if (navigator.clipboard) {
      void navigator.clipboard.writeText(JSON.stringify(row, null, 2));
    }
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1200);
  };
  return (
    <Box sx={{ bgcolor: panelBackground, px: 2, pt: 0.5, pb: 2.25, pl: 3 }}>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', lg: '1.3fr 1fr' },
          gap: 2.25,
          pt: 1.75,
        }}
      >
        <Box>
          <Typography sx={detailHeadingSx}>Body</Typography>
          <Box
            sx={{
              typography: 'mono',
              fontSize: 12.5,
              lineHeight: 1.65,
              color: 'text.primary',
              bgcolor: 'background.paper',
              border: 1,
              borderColor: 'divider',
              borderRadius: radii.lg,
              p: 1.75,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}
          >
            {row.body}
          </Box>
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 2.25,
              flexWrap: 'wrap',
              mt: 1.75,
            }}
          >
            <MetaItem label="Timestamp" value={fullTimestamp(row.timestamp)} />
            {row.traceId ? (
              <MetaItem label="Trace" value={`${row.traceId.slice(0, 16)}…`} />
            ) : null}
            {row.spanId ? (
              <MetaItem label="Span" value={row.spanId.slice(0, 12)} />
            ) : null}
            {row.attributes?.['session.id'] ? (
              <MetaItem
                label="Session"
                value={String(row.attributes['session.id'])}
              />
            ) : null}
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mt: 1.5 }}>
            {row.traceId ? (
              <Box
                component={RouterLink}
                to={`/traces/${row.traceId}`}
                sx={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 0.9,
                  height: 30,
                  px: 1.6,
                  borderRadius: radii.sm,
                  bgcolor: (t) => alpha(t.palette.primary.main, 0.12),
                  border: (t) =>
                    `1px solid ${alpha(t.palette.primary.main, 0.32)}`,
                  color: 'primary.main',
                  fontFamily: fontFamilies.display,
                  fontSize: 12.5,
                  fontWeight: 600,
                  cursor: 'pointer',
                  textDecoration: 'none',
                  '&:hover': {
                    bgcolor: (t) => alpha(t.palette.primary.main, 0.2),
                  },
                }}
              >
                <TimelineIcon sx={{ fontSize: 15 }} />
                Open in trace
              </Box>
            ) : null}
            <Box
              component="span"
              role="button"
              onClick={handleCopyJson}
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.75,
                fontSize: 11.5,
                fontWeight: 600,
                color: copied ? 'success.main' : 'text.secondary',
                cursor: 'pointer',
                '&:hover': { color: copied ? 'success.main' : 'primary.main' },
              }}
            >
              {copied ? (
                <CheckIcon sx={{ fontSize: 14 }} />
              ) : (
                <ContentCopyIcon sx={{ fontSize: 13 }} />
              )}
              {copied ? 'Copied' : 'Copy JSON'}
            </Box>
          </Box>
        </Box>
        <Box>
          <Typography sx={detailHeadingSx}>
            Attributes
            <Box
              component="span"
              sx={{ color: 'text.disabled', fontWeight: 500, ml: 0.75 }}
            >
              {entries.length}
            </Box>
          </Typography>
          <Box
            sx={{
              border: 1,
              borderColor: 'divider',
              borderRadius: radii.lg,
              overflow: 'hidden',
              bgcolor: 'background.paper',
              px: 1,
              py: 0.5,
            }}
          >
            {/* Shared AttributeList: JSON-stringifies object values, truncates long
                strings with a "View more" link, and opens the repair modal for
                truncated (~60kB) JSON payloads like api_response_body. */}
            <AttributeList attributes={attributes} disableBackground />
          </Box>
        </Box>
      </Box>
    </Box>
  );
};

export default LogRowDetail;
