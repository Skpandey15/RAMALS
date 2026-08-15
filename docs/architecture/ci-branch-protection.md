# CI branch-protection contract

## Required status check

Protect the default branch with exactly one required workflow status: `ci-gate`. Backend and frontend jobs are conditional and must not be individually required.

## Required review controls

- Require at least one independent human approval.
- Do not allow bot identities to satisfy approval.
- Dismiss stale approvals after new commits.
- Require CODEOWNER approval for `.github/**`, `infrastructure/**`, authentication/security configuration, database migrations, Dockerfiles, and deployment manifests once the owning GitHub team is known.
- Block force pushes and branch deletion.

## Trust boundary

Pull-request code runs only under `pull_request`, with `contents: read`, no explicit secrets, no write-capable token, and no deployment or publishing credentials. Privileged publication and deployment must use separate trusted workflows added by their corresponding master-plan tasks.

## Evidence

The repository test `scripts/ci/test-change-detection.sh` proves backend-only, frontend-only, and docs-only classification. `scripts/ci/verify-workflow-security.py` rejects privileged PR triggers, write permissions, explicit secrets, and action references not pinned to a full commit SHA.

