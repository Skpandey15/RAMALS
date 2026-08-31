import { AdminDashboard } from './admin/AdminDashboard';
import { hasRealmRole, isAuthenticated, login, logout } from './auth/authClient';
import { LearnerDashboard } from './learning/LearnerDashboard';
import { RegistrationPage } from './registration/RegistrationPage';
import { OnboardingResume } from './registration/OnboardingResume';
import { isRegistrationPath } from './routing';

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

  // ADMIN is intentionally routed before learner onboarding. Admin identities must not be
  // provisioned or interpreted as professional learners merely because they authenticated.
  if (hasRealmRole('ADMIN')) {
    return (
      <AdminDashboard
        onLogout={() => {
          void logout();
        }}
      />
    );
  }

  return (
    <OnboardingResume><LearnerDashboard
      onLogout={() => {
        void logout();
      }}
    /></OnboardingResume>
  );
}
