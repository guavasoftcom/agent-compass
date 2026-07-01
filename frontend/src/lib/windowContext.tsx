import {
  createContext,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import type { WindowSelection } from '../api';

export interface WindowContextValue {
  selection: WindowSelection;
  setSelection: (next: WindowSelection) => void;
  autoRefresh: boolean;
  setAutoRefresh: (next: boolean) => void;
}

const DEFAULT_SELECTION: WindowSelection = { kind: 'preset', minutes: 60 * 24 };

const WindowContext = createContext<WindowContextValue>({
  selection: DEFAULT_SELECTION,
  setSelection: () => {},
  autoRefresh: false,
  setAutoRefresh: () => {},
});

export const useWindowContext = (): WindowContextValue =>
  useContext(WindowContext);

export interface WindowProviderProps {
  children: ReactNode;
}

export const WindowProvider = ({ children }: WindowProviderProps) => {
  const [selection, setSelection] = useState<WindowSelection>(DEFAULT_SELECTION);
  const [autoRefresh, setAutoRefresh] = useState<boolean>(false);

  const value = useMemo<WindowContextValue>(
    () => ({ selection, setSelection, autoRefresh, setAutoRefresh }),
    [selection, autoRefresh],
  );

  return (
    <WindowContext.Provider value={value}>{children}</WindowContext.Provider>
  );
};
