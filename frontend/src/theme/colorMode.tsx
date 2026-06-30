import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { createAppTheme } from './theme';

export type ColorMode = 'light' | 'dark';

export interface ColorModeContextValue {
  mode: ColorMode;
  toggle: () => void;
}

const ColorModeContext = createContext<ColorModeContextValue>({
  mode: 'light',
  toggle: () => {},
});

export const useColorMode = (): ColorModeContextValue => {
  return useContext(ColorModeContext);
};

const STORAGE_KEY = 'colorMode';

const readInitialColorMode = (): ColorMode => {
  if (typeof window === 'undefined') {
    return 'light';
  }
  const stored = window.localStorage?.getItem(STORAGE_KEY);
  if (stored === 'light' || stored === 'dark') {
    return stored;
  }
  if (window.matchMedia?.('(prefers-color-scheme: dark)').matches) {
    return 'dark';
  }
  return 'light';
};

const persistColorMode = (mode: ColorMode): void => {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage?.setItem(STORAGE_KEY, mode);
  } catch {
    // ignore quota / disabled storage
  }
};

export interface ColorModeProviderProps {
  children: ReactNode;
}

export const ColorModeProvider = ({ children }: ColorModeProviderProps) => {
  const [mode, setMode] = useState<ColorMode>(() => readInitialColorMode());

  const value = useMemo<ColorModeContextValue>(() => ({
    mode,
    toggle: () => {
      setMode((previous) => {
        const next: ColorMode = previous === 'light' ? 'dark' : 'light';
        persistColorMode(next);
        return next;
      });
    },
  }), [mode]);

  const theme = useMemo(() => createAppTheme(mode), [mode]);

  return (
    <ColorModeContext.Provider value={value}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </ColorModeContext.Provider>
  );
};
