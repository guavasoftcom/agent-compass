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
import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import { Alert, AlertTitle, Box, Button } from '@mui/material';

export interface ErrorBoundaryProps {
  children: ReactNode;
  /**
   * Shallow-compared on every update. When any value in this array changes
   * while the boundary is showing its fallback, the boundary resets and
   * re-renders `children` — e.g. AppShell passes `[location.pathname]` so
   * navigating away from a crashed page recovers automatically instead of
   * leaving the fallback stuck until a manual reload.
   */
  resetKeys?: ReadonlyArray<unknown>;
}

interface ErrorBoundaryState {
  error: Error | null;
}

const sameResetKeys = (
  previous: ReadonlyArray<unknown> | undefined,
  next: ReadonlyArray<unknown> | undefined,
): boolean => {
  if (previous === next) {
    return true;
  }
  if (!previous || !next || previous.length !== next.length) {
    return false;
  }
  for (let i = 0; i < previous.length; i++) {
    if (previous[i] !== next[i]) {
      return false;
    }
  }
  return true;
};

// React only exposes componentDidCatch / getDerivedStateFromError on class
// components — there is no hook equivalent — so this is the one component in
// the app that is a class rather than an arrow function; every other rule in
// frontend/CLAUDE.md still applies.
class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('ErrorBoundary caught a render error', error, info.componentStack);
  }

  componentDidUpdate(previousProps: ErrorBoundaryProps): void {
    if (this.state.error && !sameResetKeys(previousProps.resetKeys, this.props.resetKeys)) {
      this.setState({ error: null });
    }
  }

  handleReload = (): void => {
    window.location.reload();
  };

  render(): ReactNode {
    const { error } = this.state;
    if (error) {
      return (
        <Box
          sx={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 2,
            textAlign: 'center',
            p: 5,
            minHeight: 240,
          }}
        >
          <Alert severity="error" sx={{ width: '100%', maxWidth: 520 }}>
            <AlertTitle>Something went wrong</AlertTitle>
            This part of the dashboard hit an unexpected error and stopped rendering.
          </Alert>
          <Button variant="outlined" onClick={this.handleReload}>
            Reload page
          </Button>
        </Box>
      );
    }
    return this.props.children;
  }
}

export default ErrorBoundary;
