// Central configuration for the RAMALS MVP-0 performance harness.
// Everything is environment-driven so the same scripts run against local, CI, and the
// authoritative fixed-spec performance environment without edits.

export const config = {
  baseUrl: __ENV.RAMALS_BASE_URL || 'http://localhost:8080',
  domain: __ENV.RAMALS_DOMAIN || 'KAFKA',
  version: __ENV.RAMALS_VERSION || 'v1',

  // Keycloak token endpoint and a resource-owner-password grant for a seeded load-test learner.
  // In the authoritative environment these come from the secret store, never hard-coded.
  oidc: {
    tokenUrl:
      __ENV.RAMALS_TOKEN_URL ||
      'http://localhost:8081/realms/ramals/protocol/openid-connect/token',
    clientId: __ENV.RAMALS_CLIENT_ID || 'ramals-web-ui',
    username: __ENV.RAMALS_LOAD_USER || 'load-learner',
    password: __ENV.RAMALS_LOAD_PASSWORD || '',
  },

  // Stable metadata stamped into every baseline result so runs are comparable.
  metadata: {
    commit: __ENV.RAMALS_COMMIT || 'unknown',
    environment: __ENV.RAMALS_PERF_ENV || 'local-unqualified',
    datasetVersion: __ENV.RAMALS_DATASET_VERSION || 'mvp0-baseline-v1',
    scriptVersion: 'mvp0-perf-harness-v1',
  },
};

// Request classes and their p95 latency budgets (ms). These are engineering objectives to be
// calibrated on the authoritative environment; endpoint targets must not exceed their class budget.
export const requestClassBudgetsMs = {
  skill_map_read: 250,
  content_read: 300,
  assessment_write: 400,
  diagnostic: 500,
  recommendation_read: 250,
  mastery_read: 250,
  auth: 300,
};

export function url(path) {
  return `${config.baseUrl}${path}`;
}
