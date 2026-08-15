import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { initializeAuthentication } from './auth/authClient';
import './styles.css';

function renderApplication() {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

initializeAuthentication().then(renderApplication).catch(renderApplication);
