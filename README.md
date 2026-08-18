# RAMALS

Reliable Adaptive Multi-Agent Learning System (RAMALS) is implemented as a deterministic adaptive-learning platform first, with agentic intelligence introduced only after the MVP-0 core is proven.

## Architecture authority

- Implementation sequence: `docs/RAMALS_MVP0_Implementation_Master_Plan_v1.0.docx`
- Master design: `docs/RAMALS_MVP0_Complete_Design_Package_HLD_LLD_v1.2.docx`
- Final security design: `docs/RAMALS_MVP0_Zero_Trust_Security_Architecture_v1.1.docx`
- Technology stack: `docs/RAMALS_Technology_Stack_Architecture_v1.1.docx`

## Repository layout

- `learning-platform/`: Java 25 and Spring Boot 4.1 authoritative core.
- `web-ui/`: React, TypeScript, and Vite learner interface.
- `ramals-ai/`: documentation-only MVP-1 placeholder; it is not an MVP-0 runtime dependency.
- `knowledge/kafka/`: first-domain knowledge assets.
- `infrastructure/docker/`: local platform definitions, added in M0-T02.
- `docs/`: frozen architecture baseline plus repository-facing architecture records.

## Prerequisites

- JDK 25 (JDK 26 can run the build, but Gradle compiles with the Java 25 toolchain/release target)
- Node.js 24 and npm 11
- Docker Desktop for M0-T02 and later

## Build and test

```powershell
.\gradlew.bat check
npm --prefix web-ui ci
npm --prefix web-ui test
npm --prefix web-ui run build
```

Backend verification is split into `test` (unit), `integrationTest` (PostgreSQL/runtime-backed),
`architectureTest` (security and platform boundaries), and `governanceTest` (contracts, migrations,
release records, and repository policy). `check` runs all four tasks.

No Python or AI service is needed to build or run MVP-0.

## Start the local platform

Copy `.env.example` to `.env`, populate all required values, then run:

```powershell
docker compose --env-file .env -f infrastructure/docker/compose.yml up --build --wait
```

See `infrastructure/docker/README.md` for Keycloak bootstrap, health checks, persistence verification, and shutdown procedures.
