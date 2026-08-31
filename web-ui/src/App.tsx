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

  // Route only explicitly authorised, mutually exclusive application personas. Authentication alone
  // never implies learner access, and an identity carrying both ADMIN and LEARNER is an ambiguous
  // privileged persona that fails closed rather than inheriting whichever branch happens to run first.
  // These checks shape the UI only; backend services remain authoritative for every operation.
  const isAdmin = hasRealmRole('ADMIN');
  const isLearner = hasRealmRole('LEARNER');

  if (isAdmin && !isLearner) {
    return (
      <AdminDashboard
        onLogout={() => {
          void logout();
        }}
      />
    );
  }

  if (isLearner && !isAdmin) {
    return (
      <OnboardingResume><LearnerDashboard
        onLogout={() => {
          void logout();
        }}
      /></OnboardingResume>
    );
  }

  // Zero Trust fail-closed default: unsupported identities, identities with no application persona,
  // and ADMIN+LEARNER role collisions receive no application surface and trigger no protected
  // learner/admin API bootstrap from this route.
  return <AccessDenied />;
}
