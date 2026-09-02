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

// Resolve the top-level SSO check on every route, including public registration. Registration must
// know whether another identity is active or Keycloak will reject the new learner's later email
// action as belonging to a different authenticated user. Authentication failure still renders the
// public application; Keycloak's session is never treated as required for the registration form.
initializeAuthentication().then(renderApplication).catch(renderApplication);
