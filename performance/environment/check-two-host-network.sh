#!/usr/bin/env bash
# Runs the two halves of the R1 network contract together, which is the part nothing did.
#
#   performance/environment/check-two-host-network.sh \
#     --sut-public <ip> --sut-private <ip> --loadgen-public <ip> --key <path> [--prove-guard]
#
# compose.deploy.yml binds published ports to 127.0.0.1, and RUNBOOK-aws.md tells the load generator
# to reach the backend at http://<sut-private-ip>:8080. Both statements were committed, reviewed and
# individually correct; together they describe a run that cannot connect. compose.perf-two-host.yml
# reconciles them -- but a reconciliation that nothing exercises is just a third file making a claim.
#
# So this asserts the properties rather than the configuration:
#
#   1. the load generator can reach the backend over the private interface
#   2. it can reach Keycloak there too, which k6's setup() needs
#   3. neither port answers on the public interface
#   4. the canonical topology on its own still binds loopback only
#   5. (--prove-guard) removing the override actually breaks 1 and 2
#
# Check 5 is the one that makes the others mean something -- a connectivity test that would pass
# with the override removed is testing the security group, not the binding. It restarts the stack
# twice, so it is opt-in rather than part of the routine run.
#
# Run from the operator's workstation: it is the only host that can see both machines and the public
# interface at the same time.
set -uo pipefail

SUT_PUBLIC="" SUT_PRIVATE="" LOADGEN_PUBLIC="" KEY="" PROVE_GUARD=0

while [ $# -gt 0 ]; do
  case "$1" in
    --sut-public)     SUT_PUBLIC="${2:-}"; shift 2 ;;
    --sut-private)    SUT_PRIVATE="${2:-}"; shift 2 ;;
    --loadgen-public) LOADGEN_PUBLIC="${2:-}"; shift 2 ;;
    --key)            KEY="${2:-}"; shift 2 ;;
    --prove-guard)    PROVE_GUARD=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

for required in SUT_PUBLIC SUT_PRIVATE LOADGEN_PUBLIC KEY; do
  [ -n "${!required}" ] || { echo "missing --${required,,}" | tr '_' '-' >&2; exit 2; }
done

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FAILURES=0

pass() { printf '  ok    %s\n' "$*"; }
bad()  { printf '  FAIL  %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
step() { printf '\n=== %s\n' "$*"; }

on_loadgen() { ssh -i "${KEY}" -o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=15 "ubuntu@${LOADGEN_PUBLIC}" "$@"; }
on_sut()     { ssh -i "${KEY}" -o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=15 "ubuntu@${SUT_PUBLIC}" "$@"; }

# Reachability, as the load generator experiences it. curl rather than a TCP probe: a port that
# accepts a connection and never answers is a different fault from one nothing is listening on, and
# only one of them is the thing being checked here.
loadgen_can_reach() { # loadgen_can_reach <url>
  on_loadgen "curl -fsS --max-time 10 -o /dev/null '$1'" >/dev/null 2>&1
}

step "1-2. the load generator reaches the SUT over the private interface"

if loadgen_can_reach "http://${SUT_PRIVATE}:8080/actuator/health"; then
  pass "backend answers at ${SUT_PRIVATE}:8080"
else
  bad "backend does NOT answer at ${SUT_PRIVATE}:8080 -- k6 would measure nothing"
fi

if loadgen_can_reach "http://${SUT_PRIVATE}:8081/realms/ramals/.well-known/openid-configuration"; then
  pass "Keycloak answers at ${SUT_PRIVATE}:8081"
else
  bad "Keycloak does NOT answer at ${SUT_PRIVATE}:8081 -- setup() could not acquire tokens"
fi

step "3. neither benchmark port is exposed publicly"

# From the operator's machine, whose address the security group admits on 22 alone. A refusal here
# is the security group and the private binding agreeing; either one alone would also produce it,
# which is the point of having both.
for port in 8080 8081; do
  if curl -fsS --max-time 8 -o /dev/null "http://${SUT_PUBLIC}:${port}/" 2>/dev/null; then
    bad "port ${port} ANSWERED on the public address ${SUT_PUBLIC} -- it must not"
  else
    pass "port ${port} does not answer on the public address"
  fi
done

step "4. the canonical topology alone still binds loopback"

# Asserted against the committed file rather than a running host. This is a property of
# compose.deploy.yml that must survive every future edit to it, including edits made when no perf
# environment exists to test against.
compose_deploy="${REPO_ROOT}/deploy/compose.deploy.yml"
routable_binding="$(grep -nE '^\s*ports:' -A2 "${compose_deploy}" | grep -E '"0\.0\.0\.0:|"\$\{[A-Z_]+\}:[0-9]' || true)"
if [ -z "${routable_binding}" ] && grep -qE '"127\.0\.0\.1:' "${compose_deploy}"; then
  pass "compose.deploy.yml publishes on 127.0.0.1 only"
else
  bad "compose.deploy.yml no longer binds loopback only: ${routable_binding}"
fi

# The override must not be reachable by accident either: it has to require an explicit address, so
# that a missing variable fails the deployment instead of silently binding everywhere.
if grep -q 'RAMALS_PERF_SUT_BIND_ADDRESS:?' "${REPO_ROOT}/performance/compose.perf-two-host.yml"; then
  pass "the override refuses to start without an explicit bind address"
else
  bad "the override no longer requires RAMALS_PERF_SUT_BIND_ADDRESS; it could bind 0.0.0.0"
fi

# -- 5. the guard is meaningful ------------------------------------------------------------------------

if [ "${PROVE_GUARD}" -eq 1 ]; then
  step "5. removing the override breaks the two-host path (perturbation)"

  echo "  bringing the stack up WITHOUT compose.perf-two-host.yml..."
  on_sut "cd ~ && docker compose -f deploy/compose.deploy.yml -f performance/compose.perf-fixed.yml up -d --force-recreate backend keycloak" >/dev/null 2>&1

  # Compose recreates the containers; the application needs a moment before a refusal can be
  # attributed to the binding rather than to a service that has not finished starting.
  sleep 20

  if loadgen_can_reach "http://${SUT_PRIVATE}:8080/actuator/health"; then
    bad "backend STILL reachable without the override -- the check proves nothing"
  else
    pass "backend unreachable without the override, as it must be"
  fi

  echo "  restoring the two-host topology..."
  on_sut "cd ~ && RAMALS_PERF_SUT_BIND_ADDRESS=${SUT_PRIVATE} docker compose \
      -f deploy/compose.deploy.yml -f performance/compose.perf-fixed.yml \
      -f performance/compose.perf-two-host.yml up -d --force-recreate backend keycloak" >/dev/null 2>&1
  sleep 20

  if loadgen_can_reach "http://${SUT_PRIVATE}:8080/actuator/health"; then
    pass "backend reachable again once the override is restored"
  else
    bad "backend did NOT come back after restoring the override -- the environment is now broken"
  fi
fi

printf '\n'
if [ "${FAILURES}" -eq 0 ]; then
  echo "Two-host network contract holds."
else
  echo "${FAILURES} check(s) failed. Do not start a measured run."
  exit 1
fi
