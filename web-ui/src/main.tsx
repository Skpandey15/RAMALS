import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { initializeAuthentication } from './auth/authClient';
import { isRegistrationPath } from './routing';
import './styles.css';

function renderApplication() {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

// Registration is deliberately public. Do not make its first paint depend on Keycloak's
// third-party-cookie/silent-SSO iframe: privacy settings can delay or block that handshake and
// would otherwise leave the public form as an empty page.
if (isRegistrationPath()) {
  renderApplication();
} else {
  initializeAuthentication().then(renderApplication).catch(renderApplication);
}
