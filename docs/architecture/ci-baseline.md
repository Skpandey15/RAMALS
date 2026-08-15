# M0-T01C CI baseline evidence

Recorded on 2026-08-14 during the local CI-foundation verification pass.

| Gate | Result | Local elapsed time |
|---|---:|---:|
| Backend clean build and tests | Passed | 4.71 seconds |
| Frontend locked install, lint, tests, and production build | Passed | 38.19 seconds |
| Gitleaks 8.30.1 working-tree scan | Passed; no leaks | Less than 1 second scan time |
| Workflow syntax (`actionlint` 1.7.12) | Passed | Not baselined |
| Workflow trust-policy verification | Passed | Not baselined |
| Change detection: backend-only | Passed | Not baselined |
| Change detection: frontend-only | Passed | Not baselined |
| Change detection: docs-only | Passed | Not baselined |

These are developer-workstation measurements, not the authoritative GitHub-hosted runner baseline. Record PR workflow job durations after the first remote run and use those values for the PR feedback-time SLO.

