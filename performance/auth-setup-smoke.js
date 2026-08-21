// Authentication-only R1 smoke check. It acquires the complete learner token pool exactly as the
// benchmark scenarios do, then spends exactly one of those tokens on one real request.
//
// The single request is the whole point, and it was missing. An earlier version stopped at
// acquisition, on the reasoning that a smoke test should send no workload. It passed on an
// environment where the backend refused every token it was given: Keycloak issued them happily, so
// acquisition succeeded, and nothing ever asked the backend whether it agreed.
//
// The measured run then returned 401 to 9,599 of 9,619 requests and reported a p95 of 4 ms against
// a 250 ms budget, because rejecting a token is fast. Every latency threshold passed. One request
// here would have shown it in seconds.
//
// One is enough, and one is deliberate: this proves the credential chain end to end -- issuer,
// signature, audience, and the backend's acceptance of all three -- without measuring anything.
import http from 'k6/http';
import { fail } from 'k6';
import { config, url } from './lib/config.js';
import { acquireAccessTokenPool, authHeaders } from './lib/auth.js';

export const options = {
  vus: 1,
  iterations: 1,
};

export function setup() {
  return { tokens: acquireAccessTokenPool() };
}

export default function (data) {
  const tokens = data.tokens || [];
  if (tokens.length < 1) {
    fail('authentication setup returned an empty token pool');
  }

  // /api/v1/me is the cheapest authenticated read in the mix, and the one the 'auth' request class
  // already exercises. It touches the identity path and little else, so a failure here is about the
  // token rather than about anything downstream of it.
  const response = http.get(url('/api/v1/me'), authHeaders(tokens[0]));

  if (response.status === 401) {
    fail(
      `the backend refused a freshly issued token (401). The tokens are valid -- Keycloak minted ` +
      `${tokens.length} of them -- so this is the backend disagreeing about who issued them. On a ` +
      `two-host run that is normally the issuer: Keycloak derives 'iss' from the address the token ` +
      `was requested through, and the backend validates against RAMALS_OIDC_ISSUER_URI. Check that ` +
      `KC_HOSTNAME and RAMALS_OIDC_ISSUER_URI name the same address (see ` +
      `performance/compose.perf-two-host.yml), and that the token endpoint in use is ` +
      `${config.oidc.tokenUrl}.`,
    );
  }

  if (response.status !== 200) {
    fail(`authenticated identity read returned ${response.status}, expected 200`);
  }
}
