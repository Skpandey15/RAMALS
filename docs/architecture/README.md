# Repository architecture

Implementation-facing architecture records. The frozen AR-0 source documents remain in the parent
`docs` directory and are the authority where they differ.

| Record | What it covers |
| --- | --- |
| [ci-baseline.md](ci-baseline.md) | M0-T01C CI foundation timings, measured on a workstation |
| [ci-branch-protection.md](ci-branch-protection.md) | What branch protection enforces on `main`, the code-review practice, and PR sizing |
| [observability-correlation.md](observability-correlation.md) | The `interactionId` / `traceId` contract and how to investigate with it |
| [observability-runbook.md](observability-runbook.md) | Operating procedures for the observability surface |
| [mvp1-agent-integration-contract.md](mvp1-agent-integration-contract.md) | MVP-1 seven-question analysis and the gaps carried into MVP-2 |
| [mvp2-t01-contract-freeze.md](mvp2-t01-contract-freeze.md) | Accepted MVP-2 ownership, integration answers, state boundaries, and frozen contracts |
| [target-intelligence-loop.md](target-intelligence-loop.md) | The repository-native north-star map: every stage of the long-term intelligence loop marked `IMPLEMENTED` / `DESIGNED` / `DEFERRED` and `NOW` / `NEXT` / `LATER`, with its owning ADR, module, or migration |
