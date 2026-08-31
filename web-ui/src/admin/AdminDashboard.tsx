type AdminDashboardProps = {
  onLogout: () => void;
};

const adminAreas = [
  {
    title: 'Learner operations',
    description: 'Review learner lifecycle and onboarding operations as administrative APIs become available.',
  },
  {
    title: 'Learning content',
    description: 'Manage domains, skills, assessments, and governed learning content from one administrative surface.',
  },
  {
    title: 'Platform operations',
    description: 'Surface service health, qualification evidence, and operational controls without fabricating telemetry.',
  },
  {
    title: 'Security & access',
    description: 'Keep identity and authorization administration clearly separated from learner onboarding flows.',
  },
] as const;

/**
 * Administrative landing page.
 *
 * This component intentionally exposes no fabricated metrics or write controls. Those must be
 * connected only when corresponding authenticated ADMIN backend contracts exist.
 */
export function AdminDashboard({ onLogout }: AdminDashboardProps) {
  return (
    <main className="app admin-dashboard">
      <header className="app-header">
        <div>
          <p className="eyebrow">RAMALS · Administration</p>
          <h1>Admin dashboard</h1>
          <p className="admin-intro">
            Platform administration is isolated from the professional learner onboarding path.
          </p>
        </div>
        <button type="button" className="link-button" onClick={onLogout}>
          Log out
        </button>
      </header>

      <section className="panel" aria-labelledby="admin-overview-heading">
        <div className="panel-heading-row">
          <h2 id="admin-overview-heading">Administration areas</h2>
          <span className="admin-role-badge" aria-label="Current role: administrator">
            ADMIN
          </span>
        </div>

        <div className="admin-card-grid">
          {adminAreas.map((area) => (
            <article className="admin-card" key={area.title}>
              <h3>{area.title}</h3>
              <p>{area.description}</p>
              <span className="admin-status">Backend integration pending</span>
            </article>
          ))}
        </div>
      </section>

      <section className="panel" aria-labelledby="admin-boundary-heading">
        <h2 id="admin-boundary-heading">Access boundary</h2>
        <p>
          This dashboard is selected only for an authenticated ADMIN realm role. Backend services
          remain authoritative for authorization on every administrative operation.
        </p>
      </section>
    </main>
  );
}
