#!/usr/bin/env bash
# Prepares a fresh Ubuntu host to be the perf-standard-01 load generator.
#
# A separate machine, and that is the point rather than a convenience. On the MVP-0 workstation run
# k6, the backend and the database shared one host, so the load generator's own CPU time came out of
# the system under test and its scheduling delay was reported as the system's latency. No resource
# limit fixes that: the interference is in the measurement, not in the allocation.
#
# This box does not need to meet the SUT spec. It needs to be able to saturate the target without
# becoming the bottleneck itself, and it needs a low-latency path to it -- same region, same subnet
# where possible, because a slow network turns every class budget into a network measurement.
#
#   scp -r performance <loadgen>:~/  &&  ssh <loadgen> 'bash performance/environment/provision-loadgen.sh'
set -euo pipefail

say() { printf '\n=== %s\n' "$*"; }

if command -v k6 >/dev/null; then
  say "k6 already present: $(k6 version)"
else
  say "Installing k6 from the official repository"
  sudo apt-get update -qq
  sudo apt-get install -y -qq ca-certificates gnupg curl
  sudo install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://dl.k6.io/key.gpg | sudo gpg --dearmor -o /etc/apt/keyrings/k6.gpg
  sudo chmod a+r /etc/apt/keyrings/k6.gpg
  echo "deb [signed-by=/etc/apt/keyrings/k6.gpg] https://dl.k6.io/deb stable main" |
    sudo tee /etc/apt/sources.list.d/k6.list > /dev/null
  sudo apt-get update -qq
  sudo apt-get install -y -qq k6
fi

# psql is used by run-baseline.sh to record the database version into the baseline, and python3 to
# distil the summary. Both are small; a run that dies at the reporting step has wasted the run.
say "Installing what run-baseline.sh needs alongside k6"
sudo apt-get install -y -qq postgresql-client python3 >/dev/null
echo "  psql: $(psql --version)"
echo "  python3: $(python3 --version)"

# -- file descriptors ---------------------------------------------------------------------------------
#
# A few hundred VUs against a default 1024-descriptor limit produces connection errors that look
# exactly like the system under test refusing work. That is a measurement of this box, reported as a
# fault in the other one.

say "Raising the open-file limit for load generation"
CURRENT="$(ulimit -n)"
if [ "${CURRENT}" -lt 65535 ]; then
  printf '* soft nofile 65535\n* hard nofile 65535\n' |
    sudo tee /etc/security/limits.d/99-k6.conf > /dev/null
  echo "  raised to 65535 (currently ${CURRENT}; takes effect on next login)"
else
  echo "  already ${CURRENT}"
fi

cat <<'NEXT'

Load generator ready.

Before the first qualified run, check the path to the system under test from here -- a run whose
latency is dominated by the network measures the network:

  ping -c 20 <sut-host>          # sub-millisecond within a subnet; single digits within a region
  curl -o /dev/null -s -w 'connect=%{time_connect}s total=%{time_total}s\n' http://<sut-host>:8080/actuator/health

Then run against the SUT, from this machine:

  export RAMALS_BASE_URL=http://<sut-host>:8080
  export RAMALS_TOKEN_URL=http://<sut-host>:8081/realms/ramals/protocol/openid-connect/token
  export RAMALS_PERF_ENV=perf-standard-01
  export RAMALS_PERF_LOAD_GENERATOR_OFF_HOST=true
  ./performance/run-baseline.sh mixed-learning

The attestation has to describe the system under test, and this machine is not it. Attest on the SUT
and carry the file over:

  ssh <sut-host> 'python3 performance/environment/attest.py --require --load-generator-off-host --out /tmp/attestation.json'
  scp <sut-host>:/tmp/attestation.json ./attestation.json
  export RAMALS_PERF_ATTESTATION=./attestation.json

run-baseline.sh re-checks it -- right spec, records conformance, recent enough -- rather than taking
it at its word, and downgrades the run to local-unqualified if any of that fails.
NEXT
