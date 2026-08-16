# ADR 0004: Scan with the Trivy container image, and pin dependencies ahead of the BOM

- **Status:** Accepted
- **Date:** 2026-08-16
- **Tasks:** M0-T21

## Context

The trusted release pipeline gates publication on a Trivy CRITICAL/HIGH scan. Its first four runs
against `main` each failed for a different, genuine reason:

1. `github.repository_owner` is `Skpandey15`; OCI references must be lowercase, so hand-built image
   references were unparseable. `docker/metadata-action` normalises its own output, which masked the
   problem until the SBOM step.
2. `aquasecurity/trivy-action` delegates to `setup-trivy`, whose install script resolved the version
   and then failed fetching the release binary on the hosted runner — reproducibly, not flakily. The
   scan never executed.
3. Once the scan ran, it blocked on **25 fixable findings (2 CRITICAL, 23 HIGH)** in the
   `nginx-unprivileged` Alpine base.
4. The backend then blocked on **CVE-2026-54291 (HIGH)** in `org.postgresql:postgresql` 42.7.11, the
   version managed by the Spring Boot BOM.

## Decision

1. **Derive image references from a lowercased owner**, resolved once per job, and fail CI if
   `github.repository_owner` is interpolated directly into an image field.
2. **Run Trivy from its official container image** (`aquasec/trivy:0.64.1`) rather than the setup
   action — deterministic, no release download, one fewer third-party action in the supply chain.
3. **Remediate rather than suppress.** Move the web UI to a base verified clean, apply available
   Alpine patches at build time for the backend (its temurin pin is already the newest published
   digest), and pin the JDBC driver to 42.7.12 ahead of the BOM.

## Alternatives considered

- **`.trivyignore` for the base-image CVEs.** Fastest, but the design mandates "documented severity
  gates and an exception process rather than silently ignoring findings", and these were
  arbitrary-code-execution advisories with fixes already available.
- **Report-only scanning.** Would have produced a green pipeline while removing the enforcement that
  M0-T18 and M0-T21 exist to establish.

## Consequences

- The gate blocks on content, not configuration; both remediations were verified by scanning the
  actual images before committing.
- The explicit driver version must be removed once the Spring Boot BOM advances past 42.7.12; this is
  noted at the declaration.
- `apk --no-cache upgrade` in the backend's final stage trades a little build reproducibility for
  timely OS patches. Acceptable while the image is rebuilt per commit and re-scanned nightly.

## Verification

- Release run on `1458d32` and `36645cf` — both components published, scanned and attested, green.
- `verify-workflow-security.py` — rejects direct `repository_owner` interpolation.
- `ReleasePipelineTests` — asserts scanning, SBOM and provenance remain in the pipeline.
