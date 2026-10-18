<img src="assets/icon.svg" width="64" height="64" alt="">

# ProcureFlow

ProcureFlow is a corporate purchase-request and approval application under development. It is a Java/Spring backend portfolio project with a planned complete Angular interface, intended to make engineering decisions understandable and verifiable in interviews.

**Current status: P0–P2 accepted after independent review and passing hosted CI.** [Verified P2 run](https://github.com/kemselcuk/procureflow/actions/runs/39128889909), executable revision `7a5cc84`: **62 backend tests**, **14 Angular tests** and the real Keycloak/request protocol journey passed. Draft/create/edit/submit/cancel/reject, scoped lists/history and atomic outbox capture are implemented. Final approvals, notification delivery and full product screens remain planned; see [evidence and limitations](docs/PLAN.md#p2-execution-record).

## Project documents

- [Implementation plan and progress](docs/PLAN.md): scope, task owners, dependencies, acceptance criteria, estimates, risks and current progress.
- [Decision record](docs/DECISIONS.md): architectural reasoning, version research and important trade-offs.

Each subject has one authoritative document. This README is the entry point for running and evaluating the implemented foundation.

## Implemented foundation

- Java 21/Maven multi-module build: Spring Boot app, optional worker health scaffold and immutable schema-v1 event records.
- Public system/OIDC configuration, OpenAPI and health endpoints; protected identity, company-membership, department and monthly-budget APIs; independently reviewed P2 request lifecycle endpoints. **Approval execution is not implemented yet.**
- Flyway schema with tenant-composite constraints; separate runtime/migration/Keycloak database credentials; append-only request history, audit and outbox events. Request changes, history, audit, event/recipient capture and retry results share one transaction.
- Standalone Angular page with Keycloak Code+PKCE integration, membership status, loading/failure/retry states and component tests. Full product screens remain for P5.
- Pinned multi-stage containers and Compose, checked locally and on standard Ubuntu GitHub runners; a least-permission CI workflow repeats the foundation checks without deployment or paid storage.

## Intended product

Employees submit purchase requests. Department managers approve requests within their department; requests above EUR 1,000 additionally require a different finance approver. Company administrators manage departments, memberships and sample monthly budgets. A requester cannot approve their own request.

All company and department budgets are fictional application data. Approvals update database records and never initiate payments. The real infrastructure spending limit is documented separately in the [plan](docs/PLAN.md#cost-and-cloud-boundary).

The application will include company isolation, role-based authorization, request history, a budget overview, an approval inbox and notifications. The English interface will use a simple corporate design.

## Technology to purpose to evidence

Status is deliberately scoped: P1 identity/company APIs and P2 request/history boundaries and command races are accepted. Final-approval concurrency and notification recovery follow in P3/P4. Observed runs and limitations are recorded in the [P0](docs/PLAN.md#p0-execution-evidence), [P1](docs/PLAN.md#p1-execution-record) and [P2 evidence](docs/PLAN.md#p2-execution-record). No performance, coverage or reliability result is claimed.

| Technology | Intended purpose | Code / configuration | Evidence / remaining gate |
| --- | --- | --- | --- |
| Java 21, Spring Boot, Maven | Modular monolith and separate notification worker | [Build](pom.xml), [app](backend/app), [worker](backend/notification-worker) | Java 21.0.12.1 builds; P2 tests prove request/history/audit/outbox rollback locally and on hosted CI. Budget charging follows in P3 |
| Spring Security, Keycloak/OIDC | Login, validated JWTs and company-scoped permissions | [Identity](backend/app/src/main/java/com/procureflow/app/identity), [realm](infra/keycloak/procureflow-realm.json), [adapter boundary](frontend/src/app/authentication.ts) | [Security tests](backend/app/src/test/java/com/procureflow/app/IdentityAccessIT.java), [real-provider probe](scripts/oidc_smoke.py) and independent R1/R2 reviews passed; [P1 evidence](docs/PLAN.md#p1-execution-record) |
| JPA/Hibernate, PostgreSQL | Persistence, tenant relationships and concurrency control | [Budget mappings](backend/app/src/main/java/com/procureflow/app/budgets), [access queries](backend/app/src/main/java/com/procureflow/app/identity/ActorAccess.java) | Real PostgreSQL 18.6 checks cover composite tenant FKs, current membership, authorization locking and budget-limit constraints; final-approval concurrency follows in P3 |
| Flyway | Reproducible schema and constraints | [Migrations](backend/app/src/main/resources/db/migration), [integration test](backend/app/src/test/java/com/procureflow/app/FoundationIT.java) | Empty-database V1–V3 migration, validation and no-op remigration passed locally/hosted; tenant relationships and submitted-content/unfinished-command guards exercised directly. Existing-product upgrade checks follow later phases |
| Angular/TypeScript | Complete product UI (planned) | [Foundation/login page](frontend/src/app), [authentication tests](frontend/src/app/authentication.spec.ts) | Type checks, 14 component/service/interceptor tests and production build passed locally/hosted; real-browser product journeys remain planned |
| Transactional outbox; RabbitMQ planned | Capture committed events, then publish/recover notifications | [Capture](backend/app/src/main/java/com/procureflow/app/requests/RequestEvents.java), [contract](backend/event-contracts/src/main/java/com/procureflow/events/RequestEvent.java) | P2 event/recipient capture and rollback pass without a broker. Relay, RabbitMQ, redelivery and stopped-worker recovery remain P4 |
| Mailpit | Inspect locally delivered demo email | Local development services | Expected messages visible after worker recovery; SMTP limitations documented |
| JUnit, Mockito, Testcontainers | Verify behavior at its real boundary | [HTTP](backend/app/src/test/java/com/procureflow/app/system/FoundationHttpBoundaryTest.java), [migrations/OpenAPI](backend/app/src/test/java/com/procureflow/app/FoundationIT.java), [security/database](backend/app/src/test/java/com/procureflow/app/IdentityAccessIT.java), [requests](backend/app/src/test/java/com/procureflow/app/RequestLifecycleIT.java) | All 62 backend tests passed together on [P2 hosted CI](https://github.com/kemselcuk/procureflow/actions/runs/39128889909), including 29 request cases. Final-approval acceptance remains P3 |
| Playwright | Browser-level checks of critical user journeys | `e2e/` | Login, submission, approval, denial and notification reports |
| Docker Compose | Reproduce the local foundation, later the complete product | [Compose](compose.yaml), [smoke check](scripts/check-foundation.py) | Five healthy services, API/OpenAPI/proxy/static, real Code+PKCE/request/replay/submit/reject/history/access denials, refresh/logout/revocation and isolated cleanup passed locally/hosted; not a complete-product claim |
| GitHub Actions | Repeat foundation/identity/request checks; browser checks follow in P6 | [Workflow](.github/workflows/ci.yaml) | [Passing P2 run](https://github.com/kemselcuk/procureflow/actions/runs/39128889909): backend, frontend and isolated container/OIDC jobs; executable revision `7a5cc84` |
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

This builds three application images plus the pinned PostgreSQL image with its initialization hook, and starts app/PostgreSQL/frontend/optional worker plus pinned Keycloak in a unique local project. Secrets are generated only in memory. It checks readiness/API/OpenAPI/static routing and the real provider's authorization-code + S256 exchange, code replay denial, refresh/logout and membership revocation. The P2 journey also creates/replays/submits a draft, reads manager history, rejects the request and verifies foreign-company/private-draft/admin denials. The OIDC probe drives real HTTP login forms/protocol calls, **not a rendered-browser test of the Angular adapter**. The checker removes **only its own test containers and database volume** in cleanup. It leaves images/build cache in Docker and writes `target/foundation-smoke.json` after success. The worker check proves process readiness only, not notifications. Loopback ports are isolated; Keycloak/frontend ports are selected before realm import so issuer/redirect URLs remain exact.

For an interactive local/demo stack, set these six environment variables using private terminal input or your local secret manager: `POSTGRES_PASSWORD`, `APP_DB_PASSWORD`, `MIGRATION_DB_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `DEMO_PASSWORD`. Use distinct local-only database/admin passwords, not an account password; never paste them into chat, committed files or shared logs. `DEMO_PASSWORD` is the password you choose for the eight fictional demo identities. `.env.example` contains variable names only; no credentials are supplied by the repository.

```sh
docker compose up --build --wait
```

Open `http://localhost:4200`, `http://localhost:8080/v3/api-docs` and local Keycloak at `http://localhost:8180`. Default ports are 4200, 8080, 5432 and 8180; override `FRONTEND_PORT`, `APP_PORT`, `POSTGRES_PORT` or `KEYCLOAK_PORT` before startup if occupied. The frontend proxies API calls to the backend. `docker compose --profile worker up --build --wait` also starts the process-only worker scaffold. Broker/email delivery are not included yet.

The Compose configuration explicitly enables the `local-demo` application profile and imports a local-only Keycloak realm. Fictional accounts are `a-employee`, `a-manager`, `a-finance`, `a-admin` for Larkvane, and the corresponding `b-…` accounts for Northstar. Each company has engineering/operations departments. Business roles live in the application database, not in token role claims. The Keycloak administration account is `local-admin` with your `KEYCLOAK_ADMIN_PASSWORD`; application company administrators are not Keycloak administrators. Account/password management stays in Keycloak. Realm startup import skips an existing realm; changing environment passwords does not reset existing users.

Host application runs without `local-demo` do not seed company data. Unknown valid identities may self-register, but cannot access company endpoints until an administrator explicitly assigns an active membership. Company administration endpoints and exact scopes are in the [P1 matrix](docs/PLAN.md#p1-endpointaction-matrix). Administrative screens are still planned for P5; OpenAPI/raw API calls are the current interface.

Request API inputs/scopes are in the [P2 matrix](docs/PLAN.md#p2-endpointaction-and-retry-contract). Create/submit require a UUID `Idempotency-Key`; identical retries preserve the original result/correlation ID, even if the resource has since changed. Follow with GET for current state. Edit/cancel/reject use the last observed `version` and return 409 for stale commands. A `retryable_conflict` means retry after the competing transaction settles, retaining the same create/submit key. Request forms/inbox screens and approval endpoints remain planned.

Keep the same password environment when reusing the volume; do not regenerate it between restarts. `docker compose down` stops the development stack and preserves its databases. **Destructive local reset:** `docker compose down --volumes` deletes this project's application and Keycloak databases; use it only for data you intend to discard. Existing P0-only volumes do not contain the new separated database roles because PostgreSQL init scripts run only on an empty volume: migrate roles deliberately or reset only your disposable local data. Never silently delete a volume to bypass this prerequisite.

For frontend hot reload, keep the backend on port 8080 and run `npm start` from `frontend/` after stopping the frontend container; the development proxy forwards `/api/**` and OpenAPI to that backend. The service is loopback-only.

## Product acceptance (planned)

The release evidence will cover three required scenarios:

1. Concurrent final approvals sharing one budget leave total usage within its limit and charge each approved request once.
2. Users cannot read or change another company's data or perform actions outside their role and department.
3. Notifications committed while the worker is stopped are completed after it restarts. Duplicate event delivery is safe for database effects; email delivery has the limitations recorded in the decision log.

The explicit local-demo profile currently contains two companies, multiple departments and distinct role holders. Demo threshold/budget-approval fixtures follow in P3.

Product architecture/transaction diagrams follow the real implementation during delivery. A demo video is not a deliverable.

Created in [T3 Code](https://t3.codes).
