import { useState } from 'react';
import type { ReactNode } from 'react';
import { Box, Typography } from '@mui/material';

interface CollapsibleSectionProps {
  title: string;
  // Count rendered as plain mono text after the title; `countTitle` becomes its
  // native tooltip (the Tokens section puts the four-way breakdown there).
  count?: number;
  countTitle?: string;
  tone?: 'token' | 'tool' | 'error';
  icon?: ReactNode;
  defaultCollapsed?: boolean;
  children: ReactNode;
}

// Collapsible drawer section: the whole header row is the toggle, the chevron
// rotates to point right while collapsed. The header is a real <button> (not a
// click-handling <Typography>) so it is reachable by Tab and operable by
// Enter/Space — Logs defaults to collapsed, which made its entries unreachable
// by keyboard entirely. Expand state is plain local state — the drawer keys its
// content by span id, so every section resets to its default whenever a
// different span is selected.
const CollapsibleSection = ({
  title,
  count,
  countTitle,
  tone,
  icon,
  defaultCollapsed,
  children,
}: CollapsibleSectionProps) => {
  const [collapsed, setCollapsed] = useState(defaultCollapsed ?? false);
  const toneColor =
    tone === 'token'
      ? 'warning.main'
      : tone === 'tool'
        ? 'info.main'
        : tone === 'error'
          ? 'error.main'
          : 'text.secondary';
  return (
    <Box>
      <Typography
        component="button"
        type="button"
        aria-expanded={!collapsed}
        onClick={() => setCollapsed((v) => !v)}
        sx={{
          typography: 'eyebrowSm',
          color: toneColor,
          display: 'flex',
          alignItems: 'center',
          gap: 0.9,
          cursor: 'pointer',
          userSelect: 'none',
          mb: 1.1,
          width: '100%',
          p: 0,
          border: 'none',
          bgcolor: 'transparent',
          textAlign: 'left',
        }}
      >
        <Box
          component="svg"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={2.4}
          sx={{
            width: 11,
            height: 11,
            color: 'text.disabled',
            flexShrink: 0,
            transition: 'transform 0.15s ease',
            transform: collapsed ? 'rotate(-90deg)' : 'none',
          }}
        >
          <path d="M6 9l6 6 6-6" />
        </Box>
        {icon}
        {title}
        {count != null ? (
          <Box
            component="span"
            title={countTitle}
            sx={{
              typography: 'mono',
              fontSize: 10.5,
              letterSpacing: 0,
              color: tone ? toneColor : 'text.disabled',
            }}
          >
            {count.toLocaleString()}
          </Box>
        ) : null}
      </Typography>
      {collapsed ? null : children}
    </Box>
  );
};

export default CollapsibleSection;
