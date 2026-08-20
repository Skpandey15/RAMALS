# CI branch-protection contract

## Required status check

Protect the default branch with exactly one required workflow status: `ci-gate`. Backend and frontend jobs are conditional and must not be individually required.

## What is enforced on `main` today

Applied 2026-08-20. Until then this document described a contract that did not exist: `main` had no
branch protection and no rulesets at all, so every control below was a convention. That is worth
recording rather than quietly fixing, because a documented control nobody enabled is more dangerous
than an absent one — it is relied upon.

| Control | State |
| --- | --- |
| `ci-gate` required, and the branch must be up to date | ✅ enforced |
| Pull request required before merging | ✅ enforced |
| Stale approvals dismissed on new commits | ✅ enforced |
| Conversation resolution required | ✅ enforced |
| Force pushes and branch deletion | ✅ blocked |
| Administrators bound by the above | ✅ enforced (`enforce_admins` off means only the *approval count* is bypassable, and it is zero) |
| **At least one independent human approval** | ⬜ **not enforced — required count is 0** |
| **CODEOWNER approval for sensitive paths** | ⬜ not enforced; no `CODEOWNERS` file exists |

The last two are deliberate, not forgotten. GitHub does not allow a pull request author to approve
their own pull request, so on a single-maintainer repository a required count of 1 blocks every
merge. Raising it to 1 is the correct move the moment a second reviewer exists, and is a one-line
change to the protection payload.

- Do not allow bot identities to satisfy approval.
- Require CODEOWNER approval for `.github/**`, `infrastructure/**`, authentication/security configuration, database migrations, Dockerfiles, and deployment manifests once the owning GitHub team is known.

## Code review

`ci-gate` proves the tests that exist still pass. It cannot tell you whether the change is correct,
so every pull request also gets a review pass.

Sourcery reviews pull requests automatically, but it is rate-limited by diff volume and **fails
open**: when the weekly limit is reached it posts a notice and a generated summary that reads like a
review while containing no findings. Do not treat the presence of a Sourcery comment as evidence
that a review happened — check for actual findings.

When Sourcery has not reviewed a change, run `/code-review <pr> high` locally before merging. On
PR #70 that pass found four issues, two of them regressions introduced by the branch itself,
including a circuit breaker that opened on the caller's own expired deadline and would have
disabled tutoring for every learner under load.

## Pull request size

Keep changes small enough to review. Beyond the usual argument, there is a mechanical one here:
Sourcery's quota is measured in diff characters per week, so a few large pull requests exhaust it
and leave the rest of the week unreviewed. A change that cannot be reviewed is not safer for being
large.

## Trust boundary

Pull-request code runs only under `pull_request`, with `contents: read`, no explicit secrets, no write-capable token, and no deployment or publishing credentials. Privileged publication and deployment must use separate trusted workflows added by their corresponding master-plan tasks.

## Evidence

The repository test `scripts/ci/test-change-detection.sh` proves backend-only, frontend-only, and docs-only classification. `scripts/ci/verify-workflow-security.py` rejects privileged PR triggers, write permissions, explicit secrets, and action references not pinned to a full commit SHA.

