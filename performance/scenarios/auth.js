import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { url } from '../lib/config.js';
import { acquireAccessToken, acquireAccessTokenPool, tokenForVu, authHeaders } from '../lib/auth.js';

// JWT/JWKS benchmark. The resource server validates the JWT signature (against cached JWKS), issuer,
// audience, and expiry locally on every request. Comparing a JWT-validated endpoint against the
// unauthenticated health probe isolates that per-request validation overhead. Token issuance from
// Keycloak is measured once in setup(), separately from steady-state.
const jwtValidatedRequest = new Trend('jwt_validated_request', true);
const unauthenticatedRequest = new Trend('unauthenticated_request', true);
const tokenIssuance = new Trend('token_issuance', true);

export const options = {
  scenarios: {
    // OPEN model: fixed arrival rate isolating token-validation cost.
    auth_open: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 50),
      timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
      preAllocatedVUs: Number(__ENV.PREALLOC_VUS || 20),
      maxVUs: Number(__ENV.MAX_VUS || 100),
    },
  },
  thresholds: {
    jwt_validated_request: ['p(95)<300'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  // One deliberately isolated issuance, timed. The pool that follows spreads VUs over distinct
  // subjects so JWT validation is not measured against a single cached identity.
  const started = Date.now();
  acquireAccessToken();
  tokenIssuance.add(Date.now() - started);
  return { tokens: acquireAccessTokenPool() };
}

export default function (data) {
  const validated = http.get(url('/api/v1/me'), authHeaders(tokenForVu(data.tokens)));
  jwtValidatedRequest.add(validated.timings.duration);
  check(validated, { 'jwt validated 200': (r) => r.status === 200 });

  const health = http.get(url('/actuator/health'));
  unauthenticatedRequest.add(health.timings.duration);
  check(health, { 'health 200': (r) => r.status === 200 });
}
