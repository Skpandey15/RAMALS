// Authentication-only R1 smoke check. This deliberately has one no-op iteration: setup() acquires
// the complete learner token pool exactly as the benchmark scenarios do, but sends no workload
// requests to the application.
import { fail } from 'k6';
import { acquireAccessTokenPool } from './lib/auth.js';

export const options = {
  vus: 1,
  iterations: 1,
};

export function setup() {
  return { tokenCount: acquireAccessTokenPool().length };
}

export default function (data) {
  if (data.tokenCount < 1) {
    fail('authentication setup returned an empty token pool');
  }
}
