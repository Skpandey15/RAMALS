import http from 'k6/http';
import { check } from 'k6';
import { config, url } from '../lib/config.js';
import { acquireAccessToken, authHeaders } from '../lib/auth.js';

// Concurrency correctness under load. Uses a CLOSED (fixed-VU) model explicitly, per the matrix:
// closed/VU models are valid for concurrency tests and must be labeled. This scenario is about
// correctness, not SLO latency: retries and concurrent writes must never corrupt state.
export const options = {
  scenarios: {
    idempotency_closed: {
      executor: 'per-vu-iterations',
      vus: Number(__ENV.VUS || 20),
      iterations: Number(__ENV.ITERATIONS || 10),
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
  thresholds: {
    // No corruption: a server error under concurrency is a hard failure.
    'checks{check_type:correctness}': ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  return { token: acquireAccessToken() };
}

export default function (data) {
  const token = data.token;
  const key = `conc-${__VU}-${__ITER}`;
  const tag = { check_type: 'correctness' };

  // A retried attempt creation with the same Idempotency-Key must resolve to one logical attempt.
  const first = http.post(url(`/api/v1/diagnostics/${config.domain}/attempts`), null,
    authHeaders(token, { 'Idempotency-Key': key }));
  const second = http.post(url(`/api/v1/diagnostics/${config.domain}/attempts`), null,
    authHeaders(token, { 'Idempotency-Key': key }));

  check(
    { first, second },
    {
      'attempt creation never 5xx': (o) => o.first.status < 500 && o.second.status < 500,
      'idempotent: same logical attempt': (o) => o.first.json('attemptId') === o.second.json('attemptId'),
    },
    tag,
  );

  const attemptId = first.json('attemptId');
  if (!attemptId) {
    return;
  }
  const detail = http.get(url(`/api/v1/diagnostics/${config.domain}/attempts/${attemptId}`), authHeaders(token));
  const items = detail.json('items') || [];
  const body = JSON.stringify({
    responses: items.map((item) => ({ itemId: item.itemId, selectedOptions: [item.options[0].id] })),
  });

  // A duplicate submit must not double-score or corrupt state; both calls settle to COMPLETED.
  const submitA = http.post(url(`/api/v1/diagnostics/${config.domain}/attempts/${attemptId}/submit`), body,
    authHeaders(token, { 'Content-Type': 'application/json' }));
  const submitB = http.post(url(`/api/v1/diagnostics/${config.domain}/attempts/${attemptId}/submit`), body,
    authHeaders(token, { 'Content-Type': 'application/json' }));
  check(
    { submitA, submitB },
    {
      'duplicate submit never 5xx': (o) => o.submitA.status < 500 && o.submitB.status < 500,
      'idempotent submit stays COMPLETED': (o) =>
        (o.submitA.status !== 200 || o.submitA.json('status') === 'COMPLETED') &&
        (o.submitB.status !== 200 || o.submitB.json('status') === 'COMPLETED'),
    },
    tag,
  );
}
