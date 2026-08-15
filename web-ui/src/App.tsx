import { isAuthenticated, login, logout } from './auth/authClient';
import { LearnerDashboard } from './learning/LearnerDashboard';

export function App() {
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
      </main>
    );
  }

  return (
    <LearnerDashboard
      onLogout={() => {
        void logout();
      }}
    />
  );
}
