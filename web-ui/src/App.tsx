import { AdminDashboard } from './admin/AdminDashboard';
import { hasRealmRole, isAuthenticated, login, logout } from './auth/authClient';
import { LearnerDashboard } from './learning/LearnerDashboard';
import { RegistrationPage } from './registration/RegistrationPage';
import { OnboardingResume } from './registration/OnboardingResume';
import { isRegistrationPath } from './routing';

function AccessDenied() {
  return (
    <main className="app">
      <p className="eyebrow">RAMALS</p>
      <h1>Access not configured</h1>
      <p role="alert">
        Your authenticated account does not have access to this application. Contact an
        administrator if you believe this is incorrect.
      </p>
      <button
        type="button"
        onClick={() => {
          void logout();
        }}
      >
        Log out
      </button>
    </main>
  );
}

export function App() {
  if (isRegistrationPath()) return <RegistrationPage />;
  if (!isAuthenticated()) {
    return (
      <main className="app">
        <p className="eyebrow">RAMALS</p>
        <h1>Adaptive learning</h1>
        <p>Deterministic adaptive learning before agentic intelligence.</p>
        <button
          type="button"
          onClick={() => {
            void login();
          }}
        >
          Log in
        </button>
        <p><a href="/register">Create a professional learner account</a></p>
      </main>
    );
  }

  // Route only explicitly authorised application personas. Authentication alone never implies
  // learner access: an instructor, content author, service identity, future role, or malformed token
  // must not fall through into the learner application simply because it is not ADMIN.
  // These checks shape the UI only; backend services remain authoritative for every operation.
  if (hasRealmRole('ADMIN')) {
    return (
      <AdminDashboard
        onLogout={() => {
          void logout();
        }}
      />
    );
  }

  if (hasRealmRole('LEARNER')) {
    return (
      <OnboardingResume><LearnerDashboard
        onLogout={() => {
          void logout();
        }}
      /></OnboardingResume>
    );
  }

  // Zero Trust fail-closed default: authenticated but unsupported identities receive no application
  // persona and no protected learner/admin API bootstrap from this route.
  return <AccessDenied />;
}
