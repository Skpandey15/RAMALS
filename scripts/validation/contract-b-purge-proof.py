#!/usr/bin/env python3
"""Executable proof of the Contract B purge semantics decided by M2-ADR-019.

Satisfies M2-ADR-017 §6 prerequisite 5, which requires the purge mechanism to *exist and be
testable* before `V037`. M2-ADR-019 §6 resolved the ordering defect in that requirement -- the
mechanism `V023`'s precedent places inside the migration cannot exist before it -- by requiring
this: an executable proof of the semantics against an isolated throwaway schema, so `V037` ships
behaviour that has been run rather than described.

WHAT THIS IS NOT. It is not production compliance and must never be reported as such. It qualifies
semantics, not the production mechanism; `V037`'s own tests replace it (M2-ADR-019 Consequences).

Isolation: a disposable PostgreSQL container, a throwaway schema, and no contact with any RAMALS
database. It creates no migration and no repository database state. Nothing here runs against
production, the T15 qualification cluster, or any developer database.

  python scripts/validation/contract-b-purge-proof.py [--keep] [--json PATH]

Stdlib only, driving psql through `docker exec`, so it needs no PostgreSQL driver -- the AI plane
deliberately has none (M2-ADR-012) and adding one for a proof would be its first.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import uuid
from datetime import UTC, datetime

IMAGE = "ramals-deploy-postgres:latest"
PW = "proof-only-throwaway-not-a-secret"
CONTAINER = f"contract-b-purge-proof-{uuid.uuid4().hex[:8]}"
DB, DBUSER = "purgeproof", "proofadmin"

# The canary stands in for restricted model output. Proof 8 asserts this exact byte sequence is
# absent from every surviving surface, so it must be distinctive enough that a partial leak is
# still a match rather than plausible noise.
CANARY = "CANARY-RESTRICTED-DIAGNOSIS-e7f19c4a-DO-NOT-PERSIST"

ADOPTED = "req-adopted-0001"      # purged by the adoption path
EXPIRED = "req-expired-0002"      # purged by the ceiling sweep
LIVE = "req-live-0003"            # still running: must survive the sweep
FRESH = "req-fresh-0004"          # terminal but inside the window: must survive the sweep


def sh(args: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, check=check)


def psql(sql: str, user: str = DBUSER, expect_failure: bool = False) -> str:
    """Runs SQL in the throwaway container. Returns stdout, or stderr when a failure is expected."""
    proc = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-v", "ON_ERROR_STOP=1",
         "-U", user, "-d", DB, "-X", "-A", "-t", "-F", "|"],
        input=sql, capture_output=True, text=True,
    )
    if expect_failure:
        if proc.returncode == 0:
            raise AssertionError("expected the statement to be rejected, but it succeeded")
        return proc.stderr.strip()
    if proc.returncode != 0:
        raise RuntimeError(f"psql failed: {proc.stderr.strip()}")
    return proc.stdout.strip()


def scalar(sql: str, user: str = DBUSER) -> str:
    out = psql(sql, user=user)
    return out.splitlines()[0].strip() if out else ""


# -- the throwaway schema: shaped like V037's tables, and deliberately not V037 -------------------

SCHEMA = f"""
CREATE SCHEMA proof;

-- Shaped like core.ai_provider_execution. Only the columns the purge semantics reason about.
CREATE TABLE proof.ai_provider_execution (
  request_id            VARCHAR(64) PRIMARY KEY,
  provider_execution_id VARCHAR(128) NOT NULL,
  custom_id             VARCHAR(128) NOT NULL,
  state                 VARCHAR(24)  NOT NULL,
  model                 VARCHAR(128) NOT NULL,
  input_tokens          INTEGER,
  output_tokens         INTEGER,
  estimated_cost_usd    NUMERIC(12,6),
  admitted_at           TIMESTAMPTZ  NOT NULL,
  terminal_at           TIMESTAMPTZ
);

-- Shaped like core.ai_execution: bounded metadata and digests, never content (V023).
CREATE TABLE proof.ai_execution (
  request_id      VARCHAR(64) PRIMARY KEY,
  status          VARCHAR(24) NOT NULL,
  response_digest CHAR(64),
  completed_at    TIMESTAMPTZ
);

-- The only RESTRICTED table (M2-ADR-018 §1). Ciphertext, and purged entire (M2-ADR-019 §1).
CREATE TABLE proof.ai_execution_result (
  request_id            VARCHAR(64) PRIMARY KEY
                          REFERENCES proof.ai_provider_execution(request_id) ON DELETE CASCADE,
  provider_execution_id VARCHAR(128) NOT NULL,
  normalized_result     BYTEA        NOT NULL,
  encryption_key_id     VARCHAR(64)  NOT NULL,
  result_digest         CHAR(64)     NOT NULL,
  stored_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  purge_after           TIMESTAMPTZ  NOT NULL
);

-- UPDATE rejected, DELETE permitted. M2-ADR-018 §9: copying V021/V022's DELETE-rejecting trigger
-- would make delete-on-adoption unimplementable.
CREATE FUNCTION proof.reject_result_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'contract B results are immutable once written' USING ERRCODE = '55000';
END; $$;
CREATE TRIGGER trg_result_immutable BEFORE UPDATE ON proof.ai_execution_result
  FOR EACH ROW EXECUTE FUNCTION proof.reject_result_update();

-- Append-only transition ledger: the durable evidence that a purge happened (M2-ADR-019 §2).
CREATE TABLE proof.ai_execution_transition (
  id          BIGSERIAL PRIMARY KEY,
  request_id  VARCHAR(64) NOT NULL,
  from_state  VARCHAR(24),
  to_state    VARCHAR(24) NOT NULL,
  actor       VARCHAR(32) NOT NULL,
  reason      VARCHAR(64),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- MECHANISM 1 -- delete on adoption (M2-ADR-019 §3).
-- Targeted, by primary key, removing exactly the row whose outcome was adopted. In production this
-- runs inside the adoption transaction; here it is its own statement so the proof can observe it.
CREATE FUNCTION proof.purge_result_on_adoption(p_request_id VARCHAR)
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE removed INTEGER;
BEGIN
  DELETE FROM proof.ai_execution_result WHERE request_id = p_request_id;
  GET DIAGNOSTICS removed = ROW_COUNT;
  -- Ledger entry only when a row was actually removed, so a repeat writes no second claim.
  IF removed > 0 THEN
    INSERT INTO proof.ai_execution_transition (request_id, from_state, to_state, actor, reason)
    VALUES (p_request_id, 'RESULT_AVAILABLE', 'PURGED_ON_ADOPTION', 'ADOPTER', 'ADOPTED');
  END IF;
  RETURN removed;
END; $$;

-- MECHANISM 2 -- the ceiling sweep (M2-ADR-019 §3 and §4).
-- Takes a window and NO row id, so it cannot be asked to delete a specific row. Rejects a
-- below-floor window the way V023 rejects retention_days < 1. Requires a terminal execution state
-- as well as age, so a live execution is never purged.
CREATE FUNCTION proof.purge_expired_results(retention_days INTEGER DEFAULT 30)
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE removed INTEGER;
BEGIN
  IF retention_days IS NULL OR retention_days < 1 THEN
    RAISE EXCEPTION 'retention_days must be at least 1, got %', retention_days
      USING ERRCODE = '22023';
  END IF;

  WITH eligible AS (
    SELECT r.request_id
      FROM proof.ai_execution_result r
      JOIN proof.ai_provider_execution e ON e.request_id = r.request_id
     WHERE r.stored_at < CURRENT_TIMESTAMP - make_interval(days => retention_days)
       AND e.state IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN_TERMINAL')
     LIMIT 500
  ), deleted AS (
    DELETE FROM proof.ai_execution_result
     WHERE request_id IN (SELECT request_id FROM eligible)
    RETURNING request_id
  )
  INSERT INTO proof.ai_execution_transition (request_id, from_state, to_state, actor, reason)
  SELECT request_id, 'RESULT_AVAILABLE', 'PURGED_ON_CEILING', 'PURGE_SWEEP', 'CEILING_REACHED'
    FROM deleted;
  GET DIAGNOSTICS removed = ROW_COUNT;
  RETURN removed;
END; $$;

-- Access matrix (M2-ADR-018 §3), against the REAL role names the image creates.
-- ramals_ai_runtime and the reporting stand-in get NO grant on the result table -- stated
-- explicitly rather than left to omission, because omission is not a testable claim.
ALTER ROLE ramals_core_runtime LOGIN PASSWORD '" + PW + "';
ALTER ROLE ramals_ai_runtime  LOGIN PASSWORD '" + PW + "';
-- Idempotent: roles are cluster-scoped, so DROP SCHEMA between negative controls leaves them.
DO $rp$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'proof_reporting') THEN
    CREATE ROLE proof_reporting LOGIN PASSWORD '" + PW + "';
  END IF;
END $rp$;  -- stands in for a reporting/analytics role
GRANT USAGE ON SCHEMA proof TO ramals_core_runtime, ramals_ai_runtime, proof_reporting;
GRANT SELECT, INSERT, DELETE ON proof.ai_execution_result TO ramals_core_runtime;
GRANT SELECT ON proof.ai_provider_execution, proof.ai_execution, proof.ai_execution_transition
  TO ramals_core_runtime, ramals_ai_runtime, proof_reporting;
GRANT INSERT ON proof.ai_execution_transition TO ramals_core_runtime;
GRANT USAGE, SELECT ON SEQUENCE proof.ai_execution_transition_id_seq TO ramals_core_runtime;
"""


def seed() -> None:
    rows = [
        (ADOPTED, "msgbatch_adopted01", "SUCCEEDED", "0 days", 16, 4, "0.000036"),
        (EXPIRED, "msgbatch_expired02", "SUCCEEDED", "45 days", 20, 6, "0.000050"),
        (LIVE, "msgbatch_live000003", "RUNNING", "45 days", 18, 0, "0.000018"),
        (FRESH, "msgbatch_fresh00004", "SUCCEEDED", "2 days", 22, 8, "0.000062"),
    ]
    stmts = []
    for rid, batch, state, age, tin, tout, cost in rows:
        terminal = "NULL" if state == "RUNNING" else f"CURRENT_TIMESTAMP - INTERVAL '{age}'"
        stmts.append(f"""
        INSERT INTO proof.ai_provider_execution VALUES
          ('{rid}', '{batch}', 'custom-{rid}', '{state}', 'claude-sonnet-5',
           {tin}, {tout}, {cost}, CURRENT_TIMESTAMP - INTERVAL '{age}', {terminal});
        INSERT INTO proof.ai_execution VALUES
          ('{rid}', '{state}', repeat('a', 64), {terminal});
        INSERT INTO proof.ai_execution_result VALUES
          ('{rid}', '{batch}',
           -- stands in for AES-256-GCM ciphertext; the canary makes a leak detectable
           convert_to('{CANARY}::{rid}', 'UTF8'),
           'key-v1', repeat('b', 64),
           CURRENT_TIMESTAMP - INTERVAL '{age}',
           CURRENT_TIMESTAMP - INTERVAL '{age}' + INTERVAL '30 days');
        INSERT INTO proof.ai_execution_transition (request_id, to_state, actor, reason)
          VALUES ('{rid}', 'RESULT_AVAILABLE', 'RECONCILER', 'RESULT_STORED');
        """)
    psql("\n".join(stmts))


# -- the eight behaviours ------------------------------------------------------------------------

def run_proofs(quiet: bool = False) -> list[dict]:
    checks: list[dict] = []

    def check(n: int, name: str, passed: bool, observed: str) -> None:
        checks.append({"n": n, "name": name, "result": "PASS" if passed else "FAIL",
                       "observed": observed})
        if not quiet:
            print(f"  {'PASS' if passed else 'FAIL'}  {n}. {name}\n        {observed}")

    # 1 -- restricted content exists before purge. A proof that purges nothing proves nothing.
    present = scalar("SELECT count(*) FROM proof.ai_execution_result;")
    readable = scalar(
        f"SELECT length(convert_from(normalized_result,'UTF8')) > 0 "
        f"FROM proof.ai_execution_result WHERE request_id = '{ADOPTED}';")
    check(1, "restricted result content exists and is readable before purge",
          present == "4" and readable == "t",
          f"result rows={present}; adopted row payload readable={readable}")

    # 2 -- adoption purge removes the complete row, not a nulled column.
    removed = scalar(f"SELECT proof.purge_result_on_adoption('{ADOPTED}');")
    remaining = scalar(f"SELECT count(*) FROM proof.ai_execution_result WHERE request_id='{ADOPTED}';")
    check(2, "adoption purge removes the complete result row",
          removed == "1" and remaining == "0",
          f"rows removed={removed}; rows remaining for adopted request={remaining}")

    # 3 -- everything M2-ADR-019 §1 keeps must survive.
    kept = psql(f"""
      SELECT e.custom_id, e.provider_execution_id, x.response_digest, e.input_tokens,
             e.output_tokens, e.estimated_cost_usd, e.state,
             (SELECT count(*) FROM proof.ai_execution_transition t
               WHERE t.request_id = e.request_id AND t.to_state = 'PURGED_ON_ADOPTION')
        FROM proof.ai_provider_execution e
        JOIN proof.ai_execution x ON x.request_id = e.request_id
       WHERE e.request_id = '{ADOPTED}';""").split("|")
    check(3, "execution identity, provider ids, digest, usage, cost and purge ledger all survive",
          len(kept) == 8 and kept[0].startswith("custom-") and kept[1].startswith("msgbatch_")
          and len(kept[2]) == 64 and kept[3] == "16" and kept[5].startswith("0.000036")
          and kept[7] == "1",
          f"custom_id={kept[0]}; batch={kept[1]}; digest_len={len(kept[2])}; "
          f"usage={kept[3]}/{kept[4]}; cost={kept[5]}; ledger_purge_entries={kept[7]}")

    # 4 -- the sweep removes only what is both terminal and beyond the window.
    swept = scalar("SELECT proof.purge_expired_results(30);")
    expired_gone = scalar(f"SELECT count(*) FROM proof.ai_execution_result WHERE request_id='{EXPIRED}';")
    fresh_kept = scalar(f"SELECT count(*) FROM proof.ai_execution_result WHERE request_id='{FRESH}';")
    check(4, "ceiling sweep removes only eligible terminal results beyond the window",
          swept == "1" and expired_gone == "0" and fresh_kept == "1",
          f"swept={swept}; expired row remaining={expired_gone}; in-window row remaining={fresh_kept}")

    # 5 -- the check most likely to be skipped, and the difference between a retention control and
    # a data-loss bug: a live execution's result is 45 days old and must still survive.
    live_kept = scalar(f"SELECT count(*) FROM proof.ai_execution_result WHERE request_id='{LIVE}';")
    live_age = scalar(f"SELECT (CURRENT_TIMESTAMP - stored_at) > INTERVAL '30 days' "
                      f"FROM proof.ai_execution_result WHERE request_id='{LIVE}';")
    check(5, "a non-terminal execution's result is never purged, however old",
          live_kept == "1" and live_age == "t",
          f"live row remaining={live_kept}; beyond ceiling by age={live_age}; state=RUNNING")

    # 6 -- idempotency, and no duplicate ledger claim.
    again_adopt = scalar(f"SELECT proof.purge_result_on_adoption('{ADOPTED}');")
    again_sweep = scalar("SELECT proof.purge_expired_results(30);")
    ledger = scalar(f"SELECT count(*) FROM proof.ai_execution_transition "
                    f"WHERE request_id='{ADOPTED}' AND to_state='PURGED_ON_ADOPTION';")
    check(6, "repeated adoption purge and sweep are idempotent and write no second ledger claim",
          again_adopt == "0" and again_sweep == "0" and ledger == "1",
          f"second adoption purge removed={again_adopt}; second sweep removed={again_sweep}; "
          f"ledger entries still={ledger}")

    # 7 -- authorization: the analytics role cannot delete, and the sweep refuses a zero window.
    # Asserted from the catalogue rather than only by probing. A probe can be denied for the wrong
    # reason -- a DELETE whose WHERE clause needs SELECT fails on the SELECT, which would let a
    # stray DELETE grant pass unnoticed. The negative control below caught exactly that.
    write_grants = psql(
        "SELECT coalesce(string_agg(DISTINCT grantee || ':' || privilege_type, ',' ORDER BY "
        "grantee || ':' || privilege_type), 'none') "
        "FROM information_schema.role_table_grants "
        "WHERE table_schema='proof' AND table_name='ai_execution_result' "
        "AND privilege_type IN ('INSERT','UPDATE','DELETE') AND grantee <> 'proofadmin';")
    only_runtime = all(g.startswith("ramals_core_runtime:") for g in write_grants.split(","))
    no_update = "UPDATE" not in write_grants
    floor = psql("SELECT proof.purge_expired_results(0);", expect_failure=True)
    reporting_read = psql("SELECT count(*) FROM proof.ai_execution_result;",
                          user="proof_reporting", expect_failure=True)
    check(7, "only the runtime role holds write grants, and the sweep rejects a below-floor window",
          only_runtime and no_update
          and "retention_days must be at least 1" in floor
          and "permission denied" in reporting_read.lower(),
          f"write grants={write_grants}; reporting SELECT denied; sweep rejected retention_days=0")

    # 8 -- no surviving surface can reconstruct the payload.
    surfaces = {
        "ai_provider_execution": "SELECT coalesce(string_agg(e::text, ' '), '') FROM proof.ai_provider_execution e;",
        "ai_execution": "SELECT coalesce(string_agg(x::text, ' '), '') FROM proof.ai_execution x;",
        "transition_ledger": "SELECT coalesce(string_agg(t::text, ' '), '') FROM proof.ai_execution_transition t;",
    }
    leaks = {name: CANARY in psql(sql) for name, sql in surfaces.items()}
    # The surviving (unpurged) rows legitimately still hold their own payloads; only the purged
    # identities must be unreconstructable.
    purged_residue = scalar(
        f"SELECT count(*) FROM proof.ai_execution_result "
        f"WHERE request_id IN ('{ADOPTED}','{EXPIRED}');")
    check(8, "purged result material is unreconstructable from any surviving surface",
          not any(leaks.values()) and purged_residue == "0",
          f"canary in surviving tables={leaks}; purged rows residue={purged_residue}")

    return checks



# -- negative controls ----------------------------------------------------------------------------
#
# M2-ADR-019 acceptance criterion 3 requires the proof to fail loudly when the mechanism is wrong.
# A suite that only ever sees a correct mechanism agrees with any implementation, including a
# broken one -- the lesson the T15 harness learned the expensive way in #164 and #165.
#
# Each mutation breaks exactly one decided behaviour, and the control asserts the corresponding
# proof turns red. A mutation that leaves every proof green is a proof that was not testing that
# behaviour at all.

MUTATIONS = [
    ("sweep drops the terminal-state test",
     """CREATE OR REPLACE FUNCTION proof.purge_expired_results(retention_days INTEGER DEFAULT 30)
        RETURNS INTEGER LANGUAGE plpgsql AS $$
        DECLARE removed INTEGER;
        BEGIN
          DELETE FROM proof.ai_execution_result
           WHERE stored_at < CURRENT_TIMESTAMP - make_interval(days => retention_days);
          GET DIAGNOSTICS removed = ROW_COUNT; RETURN removed;
        END; $$;""", 5),

    ("adoption purge nulls the payload instead of deleting the row",
     """DROP TRIGGER trg_result_immutable ON proof.ai_execution_result;
        CREATE OR REPLACE FUNCTION proof.purge_result_on_adoption(p_request_id VARCHAR)
        RETURNS INTEGER LANGUAGE plpgsql AS $$
        DECLARE removed INTEGER;
        BEGIN
          UPDATE proof.ai_execution_result SET normalized_result = convert_to('', 'UTF8')
           WHERE request_id = p_request_id;
          GET DIAGNOSTICS removed = ROW_COUNT; RETURN removed;
        END; $$;""", 2),

    ("purge cascades into the provider execution row, destroying audit metadata",
     """CREATE OR REPLACE FUNCTION proof.purge_result_on_adoption(p_request_id VARCHAR)
        RETURNS INTEGER LANGUAGE plpgsql AS $$
        DECLARE removed INTEGER;
        BEGIN
          DELETE FROM proof.ai_provider_execution WHERE request_id = p_request_id;
          GET DIAGNOSTICS removed = ROW_COUNT; RETURN removed;
        END; $$;""", 3),

    ("ledger entry written unconditionally, so a repeat double-claims",
     """CREATE OR REPLACE FUNCTION proof.purge_result_on_adoption(p_request_id VARCHAR)
        RETURNS INTEGER LANGUAGE plpgsql AS $$
        DECLARE removed INTEGER;
        BEGIN
          DELETE FROM proof.ai_execution_result WHERE request_id = p_request_id;
          GET DIAGNOSTICS removed = ROW_COUNT;
          INSERT INTO proof.ai_execution_transition (request_id, from_state, to_state, actor, reason)
          VALUES (p_request_id, 'RESULT_AVAILABLE', 'PURGED_ON_ADOPTION', 'ADOPTER', 'ADOPTED');
          RETURN removed;
        END; $$;""", 6),

    ("reporting role is granted SELECT and DELETE on the result table",
     "GRANT SELECT, DELETE ON proof.ai_execution_result TO proof_reporting;", 7),
]


def run_negative_controls() -> list[dict]:
    controls = []
    for name, mutation, expected_failing_proof in MUTATIONS:
        psql("DROP SCHEMA proof CASCADE;")
        psql(SCHEMA)
        seed()
        psql(mutation)
        try:
            checks = run_proofs(quiet=True)
            caught = any(c["n"] == expected_failing_proof and c["result"] == "FAIL"
                         for c in checks)
            detail = next(c["observed"] for c in checks if c["n"] == expected_failing_proof)
        except Exception as exc:  # a mutation may make a proof raise rather than assert
            caught, detail = True, f"proof raised: {type(exc).__name__}"
        controls.append({"mutation": name, "expected_failing_proof": expected_failing_proof,
                         "caught": caught, "detail": detail[:160]})
        print(f"  {'CAUGHT ' if caught else 'MISSED '} proof {expected_failing_proof} <- {name}")
    return controls


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="leave the container running for inspection")
    ap.add_argument("--json", default="contract-b-purge-proof.json")
    args = ap.parse_args()

    print(f"Contract B purge proof (M2-ADR-019) -- throwaway container {CONTAINER}")
    started = datetime.now(UTC).isoformat()
    try:
        # The project image runs RAMALS init scripts and refuses to start without their
        # variables. Supplying them is better fidelity than swapping in a stock image: the
        # container then creates the real ramals_core_runtime / ramals_ai_runtime roles, so the
        # access matrix is proven against the names M2-ADR-018 §3 actually governs.
        sh(["docker", "run", "-d", "--rm", "--name", CONTAINER,
            "-e", "POSTGRES_DB=" + DB, "-e", "POSTGRES_USER=" + DBUSER,
            "-e", "POSTGRES_PASSWORD=" + PW,
            "-e", "KEYCLOAK_DB_NAME=kcproof", "-e", "KEYCLOAK_DB_USER=kcproof",
            "-e", "KEYCLOAK_DB_PASSWORD=" + PW,
            "-e", "RAMALS_DB_MIGRATION_PASSWORD=" + PW,
            "-e", "RAMALS_DB_RUNTIME_PASSWORD=" + PW, IMAGE])
        # pg_isready alone is not enough: it succeeds during initdb, before the server restarts
        # for real. Waiting on an actual query is what makes the readiness check true.
        for _ in range(90):
            probe = subprocess.run(
                ["docker", "exec", "-i", CONTAINER, "psql", "-U", DBUSER, "-d", DB, "-At",
                 "-c", "SELECT 1"], capture_output=True, text=True)
            if probe.returncode == 0 and probe.stdout.strip() == "1":
                break
            time.sleep(1)
        else:
            raise RuntimeError("throwaway postgres did not become ready")

        psql(SCHEMA)
        seed()
        checks = run_proofs()
        print("\nnegative controls -- each mutation must be caught:")
        controls = run_negative_controls()

        passed = (all(c["result"] == "PASS" for c in checks)
                  and all(c["caught"] for c in controls))
        report = {
            "schema": "contract-b.purge-proof.v1",
            "decided_by": "M2-ADR-019",
            "satisfies": "M2-ADR-017 §6 prerequisite 5",
            "not_production_compliance": True,
            "started_utc": started,
            "finished_utc": datetime.now(UTC).isoformat(),
            "isolation": {"container": CONTAINER, "image": IMAGE, "schema": "proof",
                          "touched_ramals_database": False, "migration_created": False},
            "result": "PASS" if passed else "FAIL",
            "checks_passed": sum(c["result"] == "PASS" for c in checks),
            "checks_total": len(checks),
            "checks": checks,
            "negative_controls_caught": sum(c["caught"] for c in controls),
            "negative_controls_total": len(controls),
            "negative_controls": controls,
        }
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=1)
        print(f"\n{report['result']}  proofs {report['checks_passed']}/{report['checks_total']}"
              f"  negative controls {report['negative_controls_caught']}"
              f"/{report['negative_controls_total']}  -> {args.json}")
        return 0 if passed else 1
    finally:
        if not args.keep:
            sh(["docker", "rm", "-f", CONTAINER], check=False)
            print(f"torn down {CONTAINER}")


if __name__ == "__main__":
    sys.exit(main())
