import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { config, url } from '../lib/config.js';
import { acquireAccessTokenPool, tokenForVu, authHeaders, idempotencyKey } from '../lib/auth.js';

// Adaptive Decision Latency (ADL): elapsed time from acceptance of a diagnostic submission until the
// authoritative mastery snapshot and resulting recommendation are available. In RAMALS this pipeline
// (scoring -> evidence -> mastery -> confidence -> recommendation + decision record) runs
// synchronously inside the submit request, so the submit response time is the ADL.
const adaptiveDecisionLatency = new Trend('adaptive_decision_latency', true);

export const options = {
  scenarios: {
    // OPEN model (constant arrival rate) is the authoritative SLO workload: it decouples request
    // starts from response completion and avoids coordinated-omission bias.
    diagnostic_open: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 10),
      timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
      preAllocatedVUs: Number(__ENV.PREALLOC_VUS || 20),
      maxVUs: Number(__ENV.MAX_VUS || 100),
      gracefulStop: '10s',
    },
  },
  thresholds: {
    // Engineering objectives; calibrate on the authoritative environment.
    adaptive_decision_latency: ['p(50)<250', 'p(95)<500', 'p(99)<900'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  return { tokens: acquireAccessTokenPool() };
}

export default function (data) {
  const token = tokenForVu(data.tokens);

  const start = http.post(
    url(`/api/v1/diagnostics/${config.domain}/attempts`),
    null,
    authHeaders(token, { 'Idempotency-Key': idempotencyKey() }),
  );
  check(start, { 'attempt created (201/200)': (r) => r.status === 201 || r.status === 200 });
  const attemptId = start.json('attemptId');
  if (!attemptId) {
    return;
  }

  const detail = http.get(
    url(`/api/v1/diagnostics/${config.domain}/attempts/${attemptId}`),
    authHeaders(token),
  );
  check(detail, { 'attempt loaded': (r) => r.status === 200 });
  const items = detail.json('items') || [];
  const responses = items.map((item) => ({
    itemId: item.itemId,
    selectedOptions: [item.options[0].id],
  }));

  const submit = http.post(
    url(`/api/v1/diagnostics/${config.domain}/attempts/${attemptId}/submit`),
    JSON.stringify({ responses }),
    authHeaders(token, { 'Content-Type': 'application/json' }),
  );
  adaptiveDecisionLatency.add(submit.timings.duration);
  check(submit, {
    // Correctness under load: submission finalizes and returns deterministic per-skill scores.
    'submission COMPLETED': (r) => r.status === 200 && r.json('status') === 'COMPLETED',
    'per-skill scores returned': (r) => (r.json('skillScores') || []).length > 0,
  });

  const mastery = http.get(
    url(`/api/v1/me/mastery/${config.domain}/versions/${config.version}`),
    authHeaders(token),
  );
  check(mastery, { 'mastery map read': (r) => r.status === 200 });

  const recommendations = http.get(url('/api/v1/me/recommendations'), authHeaders(token));
  check(recommendations, { 'recommendations read': (r) => r.status === 200 });
}
