#!/usr/bin/env bash
# Prepares a fresh Ubuntu host to be the perf-standard-01 system under test.
#
# Deliberately provider-agnostic. Every cloud creates a VM differently, but they all hand back the
# same thing -- a fresh Ubuntu LTS box on x86_64 -- and pinning this script to one provider's CLI
# would mean rewriting it the first time somebody moves. What the provider has to supply is stated
# in RUNBOOK.md and checked here rather than assumed.
#
# Run it on the VM, not from your workstation:
#
#   scp -r performance <host>:~/  &&  ssh <host> 'bash performance/environment/provision-sut.sh'
#
# It is idempotent: re-running installs nothing twice and re-prints the attestation, which is the
# thing you actually want from a second run.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPEC="${HERE}/perf-standard-01.json"

say() { printf '\n=== %s\n' "$*"; }

[ -f "${SPEC}" ] || { echo "no spec at ${SPEC}; copy the whole performance/ directory" >&2; exit 1; }

# -- refuse early, and say why ----------------------------------------------------------------------
#
# Checking capacity before installing anything. Discovering the box is too small after twenty
# minutes of setup wastes the twenty minutes and, worse, tempts somebody into carrying on anyway.

say "Checking this host against the spec before installing anything"

CPUS="$(nproc)"
MEMORY_GIB="$(awk '/MemTotal/ {printf "%.1f", $2/1024/1024}' /proc/meminfo)"
ARCH="$(uname -m)"

MIN_CPUS="$(python3 -c "import json;print(json.load(open('${SPEC}'))['host']['min_cpus'])")"
MIN_MEMORY="$(python3 -c "import json;print(json.load(open('${SPEC}'))['host']['min_memory_gib'])")"

printf '  cpus=%s (need >= %s)\n  memory=%s GiB (need >= %s)\n  arch=%s\n' \
  "${CPUS}" "${MIN_CPUS}" "${MEMORY_GIB}" "${MIN_MEMORY}" "${ARCH}"

fatal=0
[ "${CPUS}" -ge "${MIN_CPUS}" ] || { echo "  FAIL: too few CPUs" >&2; fatal=1; }
awk -v have="${MEMORY_GIB}" -v need="${MIN_MEMORY}" 'BEGIN{exit !(have >= need)}' \
  || { echo "  FAIL: too little memory" >&2; fatal=1; }
[ "${ARCH}" = "x86_64" ] || { echo "  FAIL: architecture is ${ARCH}, spec requires x86_64" >&2; fatal=1; }

if [ "${fatal}" -ne 0 ]; then
  echo >&2
  echo "This host cannot be the reference environment. Resize it or pick another." >&2
  echo "Nothing was installed." >&2
  exit 1
fi
echo "  ok: capacity satisfies the spec"

# -- docker -----------------------------------------------------------------------------------------

if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
  say "Docker already present: $(docker --version)"
else
  say "Installing Docker from the official repository"
  # Distribution packages lag, and the attestation records the daemon version -- a baseline whose
  # runtime nobody can identify is harder to compare later.
  sudo apt-get update -qq
  sudo apt-get install -y -qq ca-certificates curl gnupg
  sudo install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg |
    sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  sudo chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${VERSION_CODENAME}") stable" |
    sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
  sudo apt-get update -qq
  sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin
  sudo usermod -aG docker "${USER}"
  echo "  note: log out and back in for group membership to apply, or use sudo docker for now"
fi

# -- keep the box quiet -------------------------------------------------------------------------------
#
# The spec's limits only decide what a run measures if nothing else on the host is competing. An
# unattended upgrade that fires mid-run does not fail the attestation -- it just quietly makes one
# baseline slower than the next, which is worse, because the number still looks usable.

say "Disabling unattended upgrades so nothing competes with a run"
if systemctl list-unit-files | grep -q unattended-upgrades; then
  sudo systemctl disable --now unattended-upgrades 2>/dev/null || true
  echo "  disabled (re-enable it when this host stops being the reference environment)"
else
  echo "  not installed; nothing to disable"
fi

# -- what is still needed ------------------------------------------------------------------------------

say "Host attestation"
python3 "${HERE}/attest.py" --spec "${SPEC}" || true

cat <<'NEXT'

The remaining failures are expected on a freshly provisioned host: the stack is not running yet,
and the load generator lives on another machine.

Next, from this host:

  docker compose -f deploy/compose.deploy.yml -f performance/compose.perf-fixed.yml up -d
  python3 performance/environment/attest.py --require --load-generator-off-host

The second command must exit 0 before any run can claim the qualified id. When it does, this host is
the reference environment and its attestation output is what registers it.
NEXT
