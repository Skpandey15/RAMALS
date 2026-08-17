#!/usr/bin/env bash
# Run what PR CI runs, before pushing.
#
# This exists because reconstructing the pipeline from memory kept missing a step -- a dependency
# audit here, a format check there -- and each miss cost a full red CI round trip. The fix is not to
# remember harder.
#
#   bash scripts/ci/verify-locally.sh              # python + contract gates
#   bash scripts/ci/verify-locally.sh --docker     # same, inside the CI Python image
#   bash scripts/ci/verify-locally.sh --backend    # also run the Java build (needs PostgreSQL env)
#
# Keep this in step with .github/workflows/reusable-python-ci.yml and reusable-contract-ci.yml. If
# they diverge, this script is worse than useless: it reports success for a pipeline that no longer
# exists.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "${HERE}/../.." && pwd)"
cd "${REPO}"

PYTHON_IMAGE="python:3.14-slim"
run_docker=false
run_backend=false
for arg in "$@"; do
  case "${arg}" in
    --docker) run_docker=true ;;
    --backend) run_backend=true ;;
    *) echo "unknown option: ${arg}" >&2; exit 2 ;;
  esac
done

# Prefer the project venv so a developer's global interpreter is never what gets verified.
#
# Each candidate must actually execute before it is accepted. The repository is bind-mounted when
# this runs in a container, so a Windows venv is visible from Linux and merely *existing* is not
# evidence that it runs -- picking it produced a wall of WSL socket errors that looked like ten
# failing gates.
PY=""
for candidate in "${RAMALS_PYTHON:-}"                  "${REPO}/ramals-ai/.venv/bin/python"                  "${REPO}/ramals-ai/.venv/Scripts/python.exe"                  "python3" "python"; do
  [ -n "${candidate}" ] || continue
  if "${candidate}" -c "import sys" >/dev/null 2>&1; then PY="${candidate}"; break; fi
done
if [ -z "${PY}" ]; then
  echo "no usable Python interpreter found" >&2
  exit 2
fi
echo "Interpreter: $("${PY}" -c 'import sys; print(sys.version.split()[0], "@", sys.executable)')"
echo

failures=0
step() { # step <name> <command...>
  local name="$1"; shift
  printf '%-34s ' "${name}"
  if output="$("$@" 2>&1)"; then
    printf 'ok\n'
  else
    printf 'FAILED\n'
    printf '%s\n' "${output}" | tail -20 | sed 's/^/    /'
    failures=$((failures + 1))
  fi
}

if [ "${run_docker}" = true ]; then
  # The CI runner is Linux. Several failures only ever appeared there -- a formatter that behaved
  # differently per platform, a path-sensitive drift check -- so this is the honest rehearsal.
  echo "Running the full gate inside ${PYTHON_IMAGE}"

  # Git Bash rewrites anything that looks like a Unix path before docker sees it, which breaks both
  # sides of this command in different ways: `-w /repo` arrives as `C:/Program Files/Git/repo`, and
  # the mount source `/d/...` is not a path Docker Desktop resolves -- it silently mounts an empty
  # directory instead, so the install finds no project and the whole gate exits quietly green-ish.
  # Convert the host path ourselves, then disable the rewriting entirely.
  HOST_REPO="${REPO}"
  if command -v cygpath >/dev/null 2>&1; then
    HOST_REPO="$(cygpath -w "${REPO}")"
  fi
  export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

  # The install is not silenced. A failure here used to short-circuit the `&&` and produce no
  # output at all, which reads exactly like a clean run.
  exec docker run --rm -v "${HOST_REPO}:/repo" -w /repo "${PYTHON_IMAGE}" \
    bash -c 'set -e
      pip install --quiet --disable-pip-version-check --root-user-action=ignore \
        -e "ramals-ai[dev]" pip-audit==2.9.0
      bash scripts/ci/verify-locally.sh'
fi

echo "Python gate"
( cd ramals-ai && "${PY}" -m ruff check . ) >/dev/null 2>&1 \
  && printf '%-34s ok\n' "  ruff check" \
  || { printf '%-34s FAILED\n' "  ruff check"; ( cd ramals-ai && "${PY}" -m ruff check . 2>&1 | tail -15 | sed 's/^/    /' ); failures=$((failures + 1)); }

pushd ramals-ai >/dev/null || exit 1
step "  ruff format --check" "${PY}" -m ruff format --check .
step "  mypy --strict" "${PY}" -m mypy
step "  pytest + coverage" "${PY}" -m pytest -q --cov
popd >/dev/null || exit 1

requirements="$(mktemp)"
trap 'rm -f "${requirements}"' EXIT
step "  export requirements" "${PY}" scripts/ci/export-python-requirements.py ramals-ai/pyproject.toml "${requirements}"
step "  pip-audit --strict" "${PY}" -m pip_audit -r "${requirements}" --strict --progress-spinner off

echo "Contract gate"
step "  openapi validation" "${PY}" -c "
from openapi_spec_validator import validate
from openapi_spec_validator.readers import read_from_filename
spec, _ = read_from_filename('contracts/ai-internal.openapi.yaml')
validate(spec)
"
step "  generated model drift" "${PY}" scripts/ci/generate-contract-models.py --check
step "  contract compatibility" "${PY}" scripts/ci/check-contract-compatibility.py
step "  workflow trust boundary" "${PY}" scripts/ci/verify-workflow-security.py

if [ "${run_backend}" = true ]; then
  echo "Backend gate"
  step "  gradle build" ./gradlew :learning-platform:build -q --console=plain
fi

echo
if [ "${failures}" -gt 0 ]; then
  echo "${failures} gate(s) failed. CI would fail for the same reason."
  exit 1
fi
echo "All local gates passed."
