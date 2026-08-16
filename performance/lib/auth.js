import http from 'k6/http';
import { fail } from 'k6';
import { config } from './config.js';

// Fetches an access token via the OIDC resource-owner-password grant. Call once in setup() and
// hand the token to VUs; token acquisition is not part of the steady-state request measurement.
export function acquireAccessToken() {
  const response = http.post(
    config.oidc.tokenUrl,
    {
      grant_type: 'password',
      client_id: config.oidc.clientId,
      username: config.oidc.username,
      password: config.oidc.password,
    },
    { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } },
  );
  if (response.status !== 200) {
    fail(`token acquisition failed: ${response.status} ${response.body}`);
  }
  return response.json('access_token');
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
