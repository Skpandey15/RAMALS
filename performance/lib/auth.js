import http from 'k6/http';
import { fail } from 'k6';
import { config } from './config.js';

// Fetches an access token via the OIDC resource-owner-password grant. Call once in setup() and
// hand the token to VUs; token acquisition is not part of the steady-state request measurement.
export function acquireAccessToken(username = config.oidc.username) {
  const response = http.post(
    config.oidc.tokenUrl,
    {
      grant_type: 'password',
      client_id: config.oidc.clientId,
      username,
      password: config.oidc.password,
    },
    { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } },
  );
  if (response.status !== 200) {
    fail(`token acquisition failed for ${username}: ${response.status} ${response.body}`);
  }
  return response.json('access_token');
}

// A token per load learner. Sharing one learner across all VUs does not measure system capacity:
// every mastery and progression write would serialise on that learner's rows behind
// `SELECT ... FOR UPDATE`, so the run would report the cost of lock contention on a single row.
// provision-load-fixtures.py creates the matching users.
export function acquireAccessTokenPool() {
  const tokens = [];
  for (let index = 0; index < config.oidc.learnerCount; index += 1) {
    tokens.push(acquireAccessToken(`${config.oidc.usernamePrefix}-${String(index).padStart(3, '0')}`));
  }
  if (tokens.length === 0) {
    fail('no load learners configured; set RAMALS_LOAD_LEARNERS');
  }
  return tokens;
}

// Spread VUs evenly over the learner pool. __VU is 1-based.
export function tokenForVu(tokens) {
  return tokens[(__VU - 1) % tokens.length];
}

export function authHeaders(token, extra = {}) {
  return { headers: { Authorization: `Bearer ${token}`, ...extra } };
}

// A per-attempt Idempotency-Key, unique across VUs and iterations. We deliberately do NOT send
// X-Interaction-ID: the server mints a canonical UUIDv7 per request, and any non-canonical value
// would be rejected. The Idempotency-Key is a free-form string on the attempt-creation path.
export function idempotencyKey() {
  return `perf-${__VU}-${__ITER}-${Date.now()}`;
}
