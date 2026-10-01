import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import '@fontsource-variable/inter';
import './styles.css';
import { App } from './App';
import { AuthProvider } from './auth';
import { LiveProvider } from './live';
import { ProjectsProvider } from './projects';
import { ToastProvider } from './toast';
import { ContextMenuProvider } from './components/ContextMenu';
import { applyInitialTheme } from './theme';
import { registerServiceWorker } from './push';

applyInitialTheme();
// Makes the app installable and receives push notifications; skipped in dev so Vite's HMR stays uncached.
if (import.meta.env.PROD) window.addEventListener('load', () => registerServiceWorker());

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <AuthProvider>
          <LiveProvider>
            <ProjectsProvider>
              <ContextMenuProvider>
                <App />
              </ContextMenuProvider>
            </ProjectsProvider>
          </LiveProvider>
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  </StrictMode>,
);
