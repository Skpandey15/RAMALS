import { isAuthenticated, login, logout } from './auth/authClient';
import { LearnerDashboard } from './learning/LearnerDashboard';
import { RegistrationPage } from './registration/RegistrationPage';
import { OnboardingResume } from './registration/OnboardingResume';

export function App() {
  if (window.location.pathname === '/register') return <RegistrationPage />;
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

  return (
    <OnboardingResume><LearnerDashboard
      onLogout={() => {
        void logout();
      }}
    /></OnboardingResume>
  );
}
