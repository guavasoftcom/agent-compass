import type { ReactNode } from 'react';
import type { SxProps, Theme } from '@mui/material';
import { Box } from '@mui/material';
import { fontFamilies } from '../../theme/typography';

export type GhostButtonTone = 'default' | 'danger';

export interface GhostButtonProps {
  children: ReactNode;
  onClick?: () => void;
  /** `default` = bordered paper button; `danger` = error-tinted variant. */
  tone?: GhostButtonTone;
  disabled?: boolean;
  type?: 'button' | 'submit' | 'reset';
  title?: string;
  /** Merged after the base styles — override size/shape here (e.g. an icon square). */
  sx?: SxProps<Theme>;
}

// Outlined "ghost" action button shared across toolbars and pagers: a bordered,
// paper-backed button whose label brightens on hover. Disabled dims it and
// suppresses the hover. Pass `sx` to retune size/shape per call site.
const GhostButton = ({
  children,
  onClick,
  tone = 'default',
  disabled = false,
  type = 'button',
  title,
  sx,
}: GhostButtonProps) => {
  const isDanger = tone === 'danger';
  return (
    <Box
      component="button"
      type={type}
      title={title}
      disabled={disabled}
      onClick={onClick}
      sx={[
        {
          display: 'inline-flex',
          alignItems: 'center',
          gap: 0.9,
          height: 30,
          px: 1.4,
          borderRadius: 1.1,
          border: 1,
          cursor: disabled ? 'default' : 'pointer',
          opacity: disabled ? 0.4 : 1,
          fontFamily: fontFamilies.display,
          fontSize: 12,
          fontWeight: isDanger ? 700 : 600,
          color: isDanger ? 'error.main' : 'text.secondary',
          borderColor: isDanger
            ? (t) => `color-mix(in srgb, ${t.palette.error.main} 40%, transparent)`
            : 'divider',
          bgcolor: isDanger
            ? (t) => `color-mix(in srgb, ${t.palette.error.main} 12%, transparent)`
            : 'background.paper',
          '& svg': { fontSize: 14 },
          '&:hover': disabled
            ? {}
            : { color: isDanger ? 'error.main' : 'text.primary' },
        },
        ...(Array.isArray(sx) ? sx : [sx]),
      ]}
    >
      {children}
    </Box>
  );
};

export default GhostButton;
