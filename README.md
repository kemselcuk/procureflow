<img src="assets/icon.svg" width="64" height="64" alt="">

# ProcureFlow

ProcureFlow is a corporate purchase-request and approval application under development. It is a Java/Spring backend portfolio project with a planned complete Angular interface, intended to make engineering decisions understandable and verifiable in interviews.

**Current status: P0 foundation accepted after independent review and passing GitHub CI.** [Verified run](https://github.com/kemselcuk/procureflow/actions/runs/39049180018) on revision `57d4362`; P1 identity/company isolation has not started. Execution evidence is recorded in the [plan](docs/PLAN.md#p0-execution-evidence). Product features remain planned until their acceptance checks pass.

## Project documents

- [Implementation plan and progress](docs/PLAN.md): scope, task owners, dependencies, acceptance criteria, estimates, risks and current progress.
- [Decision record](docs/DECISIONS.md): architectural reasoning, version research and important trade-offs.
- [Agent working agreement](AGENTS.md): permissions, delegation, file ownership, review and evidence rules.

Each subject has one authoritative document. This README is the entry point for running and evaluating the implemented foundation.

## Implemented foundation

- Java 21/Maven multi-module build: Spring Boot app, optional worker health scaffold and an intentionally empty event-contract library (contracts follow in P2).
- Public `GET /api/system`, OpenAPI JSON and health endpoints; other app routes deny by default. **There is no user login or business API yet.**
- PostgreSQL tenant-root migration managed by Flyway, validated by JPA; no demo data is automatically inserted.
- Standalone Angular foundation page with real API connectivity, loading/failure/retry states and component tests.
- Pinned multi-stage containers and Compose, checked locally and on standard Ubuntu GitHub runners; a least-permission CI workflow repeats the foundation checks without deployment or paid storage.

## Intended product

Employees submit purchase requests. Department managers approve requests within their department; requests above EUR 1,000 additionally require a different finance approver. Company administrators manage departments, memberships and sample monthly budgets. A requester cannot approve their own request.

All company and department budgets are fictional application data. Approvals update database records and never initiate payments. The real infrastructure spending limit is documented separately in the [plan](docs/PLAN.md#cost-and-cloud-boundary).

The application will include company isolation, role-based authorization, request history, a budget overview, an approval inbox and notifications. The English interface will use a simple corporate design. Engineering explanations and learning discussions with the user remain in Turkish.

## Technology to purpose to evidence

Status is deliberately scoped: foundation checks do not prove the future product's tenant isolation, budget concurrency or notification recovery. Observed runs and limitations are recorded in the [P0 evidence](docs/PLAN.md#p0-execution-evidence). No performance, coverage or reliability result is claimed.

| Technology | Intended purpose | Code / configuration | Evidence / remaining gate |
| --- | --- | --- | --- |
| Java 21, Spring Boot, Maven | Modular monolith and separate notification worker | [Build](pom.xml), [app](backend/app), [worker](backend/notification-worker) | Java 21.0.12.1 build, 3 HTTP-boundary tests and 2 real-PostgreSQL integration tests passed; business transaction boundaries remain planned |
| Spring Security, Keycloak/OIDC | Login, validated JWTs and company-scoped permissions | Identity module, frontend authentication, local realm configuration | Real OIDC login plus invalid-token, role and cross-company denial tests |
| JPA/Hibernate, PostgreSQL | Persistence, tenant relationships and concurrency control | [Company mapping](backend/app/src/main/java/com/procureflow/app/identity/persistence/CompanyEntity.java) | Schema validation and company uniqueness/nonblank constraints passed against PostgreSQL 18.6; tenant/budget behavior is not implemented |
| Flyway | Reproducible schema and constraints | [Migration](backend/app/src/main/resources/db/migration/V1__create_company.sql), [integration test](backend/app/src/test/java/com/procureflow/app/FoundationIT.java) | Empty-database migration, validation and no-op remigration passed; upgrade tests follow later migrations |
| Angular/TypeScript | Complete product UI (planned) | [Foundation page](frontend/src/app), [tests](frontend/src/app/app.spec.ts) | Type checks, 3 connection/error/retry tests and production build passed; business screens and browser journeys remain planned |
| RabbitMQ, transactional outbox | Publish committed events and recover notification processing after interruptions | Outbox relay, event contracts, notification worker | Broker outage, redelivery and stopped-worker recovery tests |
| Mailpit | Inspect locally delivered demo email | Local development services | Expected messages visible after worker recovery; SMTP limitations documented |
| JUnit, Mockito, Testcontainers | Verify behavior at its real boundary | [HTTP boundary](backend/app/src/test/java/com/procureflow/app/system/FoundationHttpBoundaryTest.java), [PostgreSQL/OpenAPI](backend/app/src/test/java/com/procureflow/app/FoundationIT.java) | Foundation tests passed; purchase acceptance scenarios remain pending |
| Playwright | Browser-level checks of critical user journeys | `e2e/` | Login, submission, approval, denial and notification reports |
| Docker Compose | Reproduce the local foundation, later the complete product | [Compose](compose.yaml), [smoke check](scripts/check-foundation.py) | All 4 foundation services became healthy; API/OpenAPI/proxy/static checks and isolated cleanup passed; not a fresh-machine or complete-product claim |
| GitHub Actions | Repeat foundation checks; browser checks follow in P6 | [Workflow](.github/workflows/ci.yaml) | [Passing hosted run](https://github.com/kemselcuk/procureflow/actions/runs/39049180018): backend, frontend and isolated container jobs; revision `57d4362` |
| Terraform, when a provider is chosen | Describe a future cloud environment | Provider-specific infrastructure, deferred | Formatting/validation evidence first; an actual deployment is a separate action |

## Local setup and checks

Prerequisites: a running Docker engine with Compose for containers/integration tests; a maintained JDK 21 and compatible Node 22 for host development; npm 10 and Python 3 for the smoke-check script. Checked locally on macOS arm64/Docker Desktop and on standard Ubuntu 24.04 GitHub runners. Check Docker Desktop's applicable licensing for your use. No paid/cloud service is needed.

Select JDK 21 explicitly before running Maven. On macOS, after installing a maintained JDK 21:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw --version
./mvnw verify
```

Maven rejects Java 25 rather than silently building with it. This session used a checksum-verified temporary Temurin 21.0.12.1+1 toolchain without replacing system Java. `verify` requires Docker and runs both HTTP slice tests and real PostgreSQL integration tests; `./mvnw test` alone does **not** prove database behavior. Reports are in `backend/app/target/surefire-reports/` and `backend/app/target/failsafe-reports/`.

From `frontend/`, using the Node version in `.nvmrc`:

```sh
npm ci
npm run typecheck
npm test
npm run build
npm audit --audit-level=high
```

To check disposable containers without a host JDK/Node installation:

```sh
python3 scripts/check-foundation.py
```

This builds all three images, starts the app/PostgreSQL/frontend/optional worker using a unique Compose project and random loopback ports, checks readiness/API/OpenAPI/static routing, and removes **only its own test containers and database volume** in cleanup. It leaves images/build cache in Docker and writes `target/foundation-smoke.json` after success. It does not stop unrelated projects or the Docker engine. The worker check proves process readiness only, not notification processing.

For an interactive foundation stack, choose a local-only password in the terminal environment (do not reuse an account password or commit credentials):

```sh
export POSTGRES_PASSWORD="$(openssl rand -hex 24)"
docker compose up --build --wait
```

Open `http://localhost:4200` and `http://localhost:8080/v3/api-docs`. The default host ports are 4200, 8080 and 5432; override `FRONTEND_PORT`, `APP_PORT` or `POSTGRES_PORT` if occupied. The frontend container proxies API calls to the backend. `docker compose --profile worker up --build --wait` also starts the optional worker scaffold, without exposing its port. No Keycloak, broker, email delivery or demo identities are included yet.

Keep the same password environment when reusing the database volume; do not regenerate it between restarts. `docker compose down` stops this development stack and preserves its database. **Destructive local reset:** `docker compose down --volumes` deletes this project's local database; use it only for data you intend to discard, for example if a disposable local password was lost.

For frontend hot reload, keep the backend on port 8080 and run `npm start` from `frontend/` after stopping the frontend container; the development proxy forwards `/api/**` and OpenAPI to that backend. The service is loopback-only.

## Product acceptance (planned)

The release evidence will cover three required scenarios:

1. Concurrent final approvals sharing one budget leave total usage within its limit and charge each approved request once.
2. Users cannot read or change another company's data or perform actions outside their role and department.
3. Notifications committed while the worker is stopped are completed after it restarts. Duplicate event delivery is safe for database effects; email delivery has the limitations recorded in the decision log.

Sample data will contain two companies, multiple departments, distinct role holders and requests around the approval threshold. Demo fixtures will be explicitly enabled for local use.

Product architecture/transaction diagrams follow the real implementation during delivery. A demo video is not a deliverable.

Created in [T3 Code](https://t3.codes).
