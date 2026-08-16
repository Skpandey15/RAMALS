import http from 'k6/http';
import { check } from 'k6';
import { config, url } from '../lib/config.js';
import { acquireAccessToken, authHeaders, idempotencyKey } from '../lib/auth.js';

// Authoritative whole-system workload: the documented MVP-0 request-class mix. This mixed-load
// scenario (not the isolated per-endpoint benchmarks) is what SLO pass/fail is judged against.
const mix = [
  { weight: 35, class: 'skill_map_read', run: (t) => progression(t) },
  { weight: 20, class: 'content_read', run: (t) => curriculumGraph(t) },
  { weight: 15, class: 'assessment_write', run: (t) => diagnosticSlice(t) },
  { weight: 10, class: 'diagnostic', run: (t) => startAttempt(t) },
  { weight: 10, class: 'recommendation_read', run: (t) => recommendations(t) },
  { weight: 5, class: 'mastery_read', run: (t) => masteryMap(t) },
  { weight: 5, class: 'auth', run: (t) => identity(t) },
];
const totalWeight = mix.reduce((sum, entry) => sum + entry.weight, 0);

export const options = {
  scenarios: {
    // OPEN model: ramping arrival rate for capacity discovery under a fixed request mix.
    mixed_open: {
      executor: 'ramping-arrival-rate',
      startRate: Number(__ENV.START_RATE || 10),
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.PREALLOC_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 300),
      stages: [
        { target: Number(__ENV.START_RATE || 10), duration: __ENV.WARMUP || '30s' }, // warm-up, discard
        { target: Number(__ENV.PEAK_RATE || 60), duration: __ENV.RAMP || '1m' },
        { target: Number(__ENV.PEAK_RATE || 60), duration: __ENV.STEADY || '2m' }, // steady-state window
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{request_class:skill_map_read}': ['p(95)<250'],
    'http_req_duration{request_class:mastery_read}': ['p(95)<250'],
    'http_req_duration{request_class:recommendation_read}': ['p(95)<250'],
    'http_req_duration{request_class:assessment_write}': ['p(95)<400'],
    'http_req_duration{request_class:diagnostic}': ['p(95)<500'],
  },
};

export function setup() {
  return { token: acquireAccessToken() };
}

export default function (data) {
  let pick = Math.random() * totalWeight;
  for (const entry of mix) {
    pick -= entry.weight;
    if (pick <= 0) {
      entry.run(data.token);
      return;
    }
  }
}

function tagged(cls) {
  return { request_class: cls };
}

function progression(token) {
  const response = http.get(
    url(`/api/v1/me/progression/${config.domain}/versions/${config.version}`),
    authHeaders(token, {}),
  );
  response.request.tags = tagged('skill_map_read');
  check(response, { 'progression read': (r) => r.status === 200 }, tagged('skill_map_read'));
}

function curriculumGraph(token) {
  const response = http.get(
    url(`/api/v1/curricula/${config.domain}/versions/${config.version}/skills`),
    { ...authHeaders(token), tags: tagged('content_read') },
  );
  check(response, { 'curriculum read': (r) => r.status === 200 }, tagged('content_read'));
}

function masteryMap(token) {
  const response = http.get(
    url(`/api/v1/me/mastery/${config.domain}/versions/${config.version}`),
    { ...authHeaders(token), tags: tagged('mastery_read') },
  );
  check(response, { 'mastery read': (r) => r.status === 200 }, tagged('mastery_read'));
}

function recommendations(token) {
  const response = http.get(url('/api/v1/me/recommendations'), {
    ...authHeaders(token),
    tags: tagged('recommendation_read'),
  });
  check(response, { 'recommendations read': (r) => r.status === 200 }, tagged('recommendation_read'));
}

function identity(token) {
  const response = http.get(url('/api/v1/me'), { ...authHeaders(token), tags: tagged('auth') });
  check(response, { 'identity read': (r) => r.status === 200 }, tagged('auth'));
}

function startAttempt(token) {
  const response = http.post(url(`/api/v1/diagnostics/${config.domain}/attempts`), null, {
    ...authHeaders(token, { 'Idempotency-Key': idempotencyKey() }),
    tags: tagged('diagnostic'),
  });
  check(response, { 'attempt created': (r) => r.status === 201 || r.status === 200 }, tagged('diagnostic'));
}

function diagnosticSlice(token) {
  const start = http.post(url(`/api/v1/diagnostics/${config.domain}/attempts`), null, {
    ...authHeaders(token, { 'Idempotency-Key': idempotencyKey() }),
    tags: tagged('assessment_write'),
  });
  const attemptId = start.json('attemptId');
  if (!attemptId) {
    return;
  }
  const detail = http.get(url(`/api/v1/diagnostics/${config.domain}/attempts/${attemptId}`), authHeaders(token));
  const items = detail.json('items') || [];
  const responses = items.map((item) => ({ itemId: item.itemId, selectedOptions: [item.options[0].id] }));
  const submit = http.post(
    url(`/api/v1/diagnostics/${config.domain}/attempts/${attemptId}/submit`),
    JSON.stringify({ responses }),
    { ...authHeaders(token, { 'Content-Type': 'application/json' }), tags: tagged('assessment_write') },
  );
  check(submit, { 'submit COMPLETED': (r) => r.status === 200 }, tagged('assessment_write'));
}
