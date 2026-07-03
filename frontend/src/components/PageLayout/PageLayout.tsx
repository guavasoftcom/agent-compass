import { Alert, Box, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';

export interface PageLayoutProps {
  /**
   * Small uppercase context line shown above the title (Aurora "eyebrow").
   * Typically "Group · Tab" — e.g. "Usage · Calls".
   */
  eyebrow?: ReactNode;
  /**
   * Omit when this page is rendered inside a SectionLayout — the section provides the
   * title row and any actions. In that mode only the subtitle / error / body render.
   */
  title?: ReactNode;
  subtitle?: ReactNode;
  meta?: ReactNode;
  actions?: ReactNode;
  error?: Error | string | null;
  children?: ReactNode;
  spacing?: number;
}

/**
 * Standard layout for every top-level page in the dashboard.
 *
 * Structure:
 *   - Optional eyebrow (uppercase violet context line) above the title.
 *   - Header row: title (h4, gradient-clipped) + optional inline meta + optional subtitle
 *     on the left, and optional right-aligned actions slot on the right.
 *   - Optional error banner (passed an Error or a string).
 *   - Page body as children.
 */
export default function PageLayout({
  eyebrow,
  title,
  subtitle,
  meta,
  actions,
  error,
  children,
  spacing = 3,
}: PageLayoutProps) {
  const errorMessage =
    error == null
      ? null
      : typeof error === 'string'
        ? error
        : (error.message ?? 'Something went wrong.');

  const hasHeader = Boolean(eyebrow || title || subtitle || meta || actions);

  return (
    <Stack spacing={spacing}>
      {hasHeader && (
        <Stack spacing={1}>
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={2}
            sx={{ alignItems: { xs: 'stretch', sm: 'flex-start' } }}
            useFlexGap
          >
            <Box>
              {eyebrow && (
                <Typography
                  component="div"
                  sx={{
                    color: 'primary.main',
                    typography: 'eyebrow',
                    mb: 0.75,
                  }}
                >
                  {eyebrow}
                </Typography>
              )}
              {(title || meta) && (
                <Stack
                  direction="row"
                  spacing={1.5}
                  sx={{ alignItems: 'baseline', flexWrap: 'wrap' }}
                  useFlexGap
                >
                  {title && (
                    <Typography
                      variant="h4"
                      sx={(theme) => ({
                        fontSize: { xs: 30, sm: 38 },
                        letterSpacing: '-1px',
                        color: theme.custom.titleColor,
                      })}
                    >
                      {title}
                    </Typography>
                  )}
                  {meta && (
                    <Typography variant="body2" color="text.secondary">
                      {meta}
                    </Typography>
                  )}
                </Stack>
              )}
            </Box>
            {actions && (
              <Stack
                direction="row"
                spacing={1.5}
                useFlexGap
                sx={{ flexWrap: 'wrap', ml: { sm: 'auto' }, alignItems: 'flex-start' }}
              >
                {actions}
              </Stack>
            )}
          </Stack>
          {subtitle && (
            <Typography variant="body2" color="text.secondary" sx={{ maxWidth: '75ch' }}>
              {subtitle}
            </Typography>
          )}
        </Stack>
      )}

      {errorMessage && <Alert severity="error">{errorMessage}</Alert>}

      {children}
    </Stack>
  );
}
