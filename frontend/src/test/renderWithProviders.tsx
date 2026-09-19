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
import type { ReactElement, ReactNode } from 'react';
import { render, type RenderResult } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { ColorModeProvider } from '../theme/colorMode';

/**
 * Wraps a view in the same provider stack `main.tsx` mounts the app under
 * (ColorModeProvider → ThemeProvider/CssBaseline, QueryClientProvider, router),
 * minus `WindowProvider` — no view reads `useWindowContext()` directly, only
 * containers do.
 *
 * The stack is passed as RTL's `wrapper` rather than wrapped around `ui`
 * directly, so the returned `rerender` keeps it — a test that re-renders a view
 * with new props (e.g. a polled query landing) still gets the theme and router.
 */
export const renderWithProviders = (ui: ReactElement): RenderResult => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  const Providers = ({ children }: { children: ReactNode }) => (
    <ColorModeProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    </ColorModeProvider>
  );

  return render(ui, { wrapper: Providers });
};
