import { useCallback, useEffect, useState } from 'react';
import {
  addIdentityRole,
  changeLearnerStatus,
  getOperationalSnapshot,
  listAdminAudit,
  listCurricula,
  listIdentities,
  listLearners,
  listSecurityAudit,
  publishCurriculum,
  removeIdentityRole,
  retireCurriculum,
  setIdentityEnabled,
  type AdminAuditEvent,
  type AdminCurriculum,
  type AdminIdentityUser,
  type AdminLearner,
  type AdminOperationalSnapshot,
  type SecurityAuditEvent,
} from './api';

type AdminDashboardProps = {
  onLogout: () => void;
};

function message(error: unknown): string {
  return error instanceof Error ? error.message : 'Administrative request failed.';
}

export function AdminDashboard({ onLogout }: AdminDashboardProps) {
  const [snapshot, setSnapshot] = useState<AdminOperationalSnapshot | null>(null);
  const [learners, setLearners] = useState<AdminLearner[]>([]);
  const [curricula, setCurricula] = useState<AdminCurriculum[]>([]);
  const [identities, setIdentities] = useState<AdminIdentityUser[]>([]);
  const [adminAudit, setAdminAudit] = useState<AdminAuditEvent[]>([]);
  const [securityAudit, setSecurityAudit] = useState<SecurityAuditEvent[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const [nextSnapshot, nextLearners, nextCurricula, nextIdentities, nextAdminAudit, nextSecurityAudit] =
        await Promise.all([
          getOperationalSnapshot(),
          listLearners(),
          listCurricula(),
          listIdentities(),
          listAdminAudit(),
          listSecurityAudit(),
        ]);
      setSnapshot(nextSnapshot);
      setLearners(nextLearners);
      setCurricula(nextCurricula);
      setIdentities(nextIdentities);
      setAdminAudit(nextAdminAudit);
      setSecurityAudit(nextSecurityAudit);
    } catch (requestError) {
      setError(message(requestError));
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const updateLearner = async (learner: AdminLearner, status: AdminLearner['status']) => {
    setError(null);
    try {
      const updated = await changeLearnerStatus(learner.learnerId, status);
      setLearners((current) => current.map((item) => (item.learnerId === updated.learnerId ? updated : item)));
      await refresh();
    } catch (requestError) {
      setError(message(requestError));
    }
  };

  const transitionCurriculum = async (curriculum: AdminCurriculum, action: 'publish' | 'retire') => {
    setError(null);
    try {
      const updated = action === 'publish'
        ? await publishCurriculum(curriculum.curriculumVersionId)
        : await retireCurriculum(curriculum.curriculumVersionId);
      setCurricula((current) =>
        current.map((item) => (item.curriculumVersionId === updated.curriculumVersionId ? updated : item)),
      );
      await refresh();
    } catch (requestError) {
      setError(message(requestError));
    }
  };

  const updateIdentityEnabled = async (identity: AdminIdentityUser) => {
    setError(null);
    try {
      const updated = await setIdentityEnabled(identity.id, !identity.enabled);
      setIdentities((current) => current.map((item) => (item.id === updated.id ? updated : item)));
      await refresh();
    } catch (requestError) {
      setError(message(requestError));
    }
  };

  const updateRole = async (
    identity: AdminIdentityUser,
    role: 'INSTRUCTOR' | 'CONTENT_AUTHOR',
  ) => {
    setError(null);
    try {
      const hasRole = identity.realmRoles.includes(role);
      const updated = hasRole
        ? await removeIdentityRole(identity.id, role)
        : await addIdentityRole(identity.id, role);
      setIdentities((current) => current.map((item) => (item.id === updated.id ? updated : item)));
      await refresh();
    } catch (requestError) {
      setError(message(requestError));
    }
  };

  return (
    <main className="app admin-dashboard">
      <header className="app-header">
        <div>
          <p className="eyebrow">RAMALS · Administration</p>
          <h1>Admin dashboard</h1>
          <p className="admin-intro">Server-authoritative administration with audited privileged operations.</p>
        </div>
        <div className="admin-actions">
          <span className="admin-role-badge">ADMIN</span>
          <button type="button" className="secondary-button" onClick={() => void refresh()} disabled={busy}>
            {busy ? 'Refreshing…' : 'Refresh'}
          </button>
          <button type="button" className="link-button" onClick={onLogout}>Log out</button>
        </div>
      </header>

      {error && <div className="error-banner" role="alert">{error}</div>}

      <section className="panel" aria-labelledby="operations-heading">
        <h2 id="operations-heading">Platform operations</h2>
        <div className="admin-metric-grid">
          <article className="admin-card"><h3>Learners</h3><strong>{snapshot?.learnersTotal ?? '—'}</strong><p>{snapshot?.learnersActive ?? 0} active · {snapshot?.learnersSuspended ?? 0} suspended · {snapshot?.learnersClosed ?? 0} closed</p></article>
          <article className="admin-card"><h3>Onboarded</h3><strong>{snapshot?.learnersOnboarded ?? '—'}</strong><p>Professional learners at ONBOARDED state</p></article>
          <article className="admin-card"><h3>Curricula</h3><strong>{snapshot?.curriculaPublished ?? '—'}</strong><p>{snapshot?.curriculaDraft ?? 0} draft · {snapshot?.curriculaRetired ?? 0} retired</p></article>
          <article className="admin-card"><h3>Security · 24h</h3><strong>{snapshot?.authorizationDenials24h ?? '—'}</strong><p>authorization denials · {snapshot?.adminActions24h ?? 0} admin actions</p></article>
        </div>
      </section>

      <section className="panel" aria-labelledby="learners-heading">
        <div className="panel-heading-row"><h2 id="learners-heading">Learner management</h2><span className="admin-status">Status changes require MFA</span></div>
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead><tr><th>Learner</th><th>Verification</th><th>Onboarding</th><th>Status</th><th>Action</th></tr></thead>
            <tbody>
              {learners.map((learner) => (
                <tr key={learner.learnerId}>
                  <td><strong>{[learner.firstName, learner.lastName].filter(Boolean).join(' ') || learner.learnerId}</strong><br /><small>{learner.email ?? 'No contact record'}</small></td>
                  <td>Email {learner.emailVerified ? '✓' : '—'} · Mobile {learner.mobileVerified ? '✓' : '—'}</td>
                  <td>{learner.onboardingState ?? 'NOT_STARTED'}</td>
                  <td>{learner.status}</td>
                  <td className="admin-inline-actions">
                    {learner.status !== 'ACTIVE' && <button type="button" onClick={() => void updateLearner(learner, 'ACTIVE')}>Activate</button>}
                    {learner.status === 'ACTIVE' && <button type="button" onClick={() => void updateLearner(learner, 'SUSPENDED')}>Suspend</button>}
                    {learner.status !== 'CLOSED' && <button type="button" onClick={() => void updateLearner(learner, 'CLOSED')}>Close</button>}
                  </td>
                </tr>
              ))}
              {!busy && learners.length === 0 && <tr><td colSpan={5}>No learners found.</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel" aria-labelledby="content-heading">
        <div className="panel-heading-row"><h2 id="content-heading">Learning content</h2><span className="admin-status">Governed lifecycle</span></div>
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead><tr><th>Domain</th><th>Version</th><th>Status</th><th>Published</th><th>Action</th></tr></thead>
            <tbody>
              {curricula.map((curriculum) => (
                <tr key={curriculum.curriculumVersionId}>
                  <td>{curriculum.domainCode}</td><td>{curriculum.versionCode}</td><td>{curriculum.status}</td>
                  <td>{curriculum.publishedAt ? new Date(curriculum.publishedAt).toLocaleString() : '—'}</td>
                  <td>{curriculum.status === 'DRAFT' ? <button type="button" onClick={() => void transitionCurriculum(curriculum, 'publish')}>Publish</button> : curriculum.status === 'PUBLISHED' ? <button type="button" onClick={() => void transitionCurriculum(curriculum, 'retire')}>Retire</button> : 'Immutable'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel" aria-labelledby="identity-heading">
        <div className="panel-heading-row"><h2 id="identity-heading">Security & access</h2><span className="admin-status">Mutations require MFA</span></div>
        <p>ADMIN, SERVICE, and LEARNER privilege boundaries are not editable here. Staff administration is limited to INSTRUCTOR and CONTENT_AUTHOR.</p>
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead><tr><th>Identity</th><th>Enabled</th><th>Realm roles</th><th>Staff role actions</th></tr></thead>
            <tbody>
              {identities.map((identity) => (
                <tr key={identity.id}>
                  <td><strong>{identity.username ?? identity.id}</strong><br /><small>{identity.email ?? identity.id}</small></td>
                  <td><button type="button" onClick={() => void updateIdentityEnabled(identity)} disabled={identity.realmRoles.includes('ADMIN') || identity.realmRoles.includes('SERVICE')}>{identity.enabled ? 'Disable' : 'Enable'}</button></td>
                  <td>{identity.realmRoles.filter((role) => ['ADMIN', 'LEARNER', 'INSTRUCTOR', 'CONTENT_AUTHOR', 'SERVICE'].includes(role)).join(', ') || 'none'}</td>
                  <td className="admin-inline-actions">
                    <button type="button" onClick={() => void updateRole(identity, 'INSTRUCTOR')} disabled={identity.realmRoles.includes('ADMIN') || identity.realmRoles.includes('SERVICE') || identity.realmRoles.includes('LEARNER')}>{identity.realmRoles.includes('INSTRUCTOR') ? 'Remove instructor' : 'Add instructor'}</button>
                    <button type="button" onClick={() => void updateRole(identity, 'CONTENT_AUTHOR')} disabled={identity.realmRoles.includes('ADMIN') || identity.realmRoles.includes('SERVICE') || identity.realmRoles.includes('LEARNER')}>{identity.realmRoles.includes('CONTENT_AUTHOR') ? 'Remove content author' : 'Add content author'}</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel" aria-labelledby="audit-heading">
        <h2 id="audit-heading">Audit & security evidence</h2>
        <div className="admin-audit-grid">
          <div><h3>Administrative activity</h3><ul className="admin-event-list">{adminAudit.slice(0, 12).map((event) => <li key={event.id}><strong>{event.action}</strong> · {event.outcome}<br /><small>{new Date(event.createdAt).toLocaleString()} · {event.interactionId ?? 'no interaction id'}</small></li>)}</ul></div>
          <div><h3>Security activity</h3><ul className="admin-event-list">{securityAudit.slice(0, 12).map((event) => <li key={event.id}><strong>{event.eventType}</strong> · {event.outcome}<br /><small>{event.statusCode ?? '—'} · {event.route ?? '—'} · {new Date(event.createdAt).toLocaleString()}</small></li>)}</ul></div>
        </div>
      </section>

      <section className="panel" aria-labelledby="admin-boundary-heading">
        <h2 id="admin-boundary-heading">Access boundary</h2>
        <p>React controls navigation only. Every administrative API independently verifies the authenticated ADMIN authority, and privileged mutations additionally require MFA at the backend.</p>
      </section>
    </main>
  );
}
