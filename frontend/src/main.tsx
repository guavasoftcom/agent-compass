import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import './theme/fonts'; // self-hosted Sora + Space Grotesk (bundled, no CDN)
import App from './App';
import { ColorModeProvider } from './theme/colorMode';
import { WindowProvider } from './lib/windowContext';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, refetchOnWindowFocus: false },
  },
});

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error('Root element #root not found in index.html');
}

ReactDOM.createRoot(rootElement).render(
  <React.StrictMode>
    <ColorModeProvider>
      <QueryClientProvider client={queryClient}>
        <WindowProvider>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </WindowProvider>
      </QueryClientProvider>
    </ColorModeProvider>
  </React.StrictMode>,
);
