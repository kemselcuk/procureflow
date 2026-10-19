<img src="assets/icon.svg" width="64" height="64" alt="">

# ProcureFlow

ProcureFlow is a corporate purchase-request and approval application under development. It is a Java/Spring backend portfolio project with a planned complete Angular interface, intended to make engineering decisions understandable and verifiable in interviews.

**Current status: P0–P3 accepted after independent review and passing hosted CI.** [Verified P3 run](https://github.com/kemselcuk/procureflow/actions/runs/39132402726), executable revision `c3e35df`: **106 backend tests**, **14 Angular tests** and the real Keycloak/request/approval/budget journey passed. Department/finance approval and atomic budget charging are implemented; see [P3 evidence and limitations](docs/PLAN.md#p3-execution-record). Notification delivery and full product screens remain planned.

The [P4 database foundation](docs/PLAN.md#p4-execution-record) is separately reviewed and locally/hosted verified: [run 39158792532](https://github.com/kemselcuk/procureflow/actions/runs/39158792532), revision `adf11c3`, passed **117 backend tests**, **14 Angular tests** and fresh Compose/Keycloak regression. A data-preserving V4→V5 database upgrade also passed locally. This is not full P4 acceptance; broker publication, notification APIs, consumption and SMTP delivery remain unimplemented in that revision.

The notification API is independently reviewed and locally/hosted verified with **133 backend tests**: own-notification list/read and company-admin backlog counts. [Hosted run 39161302004](https://github.com/kemselcuk/procureflow/actions/runs/39161302004) passed on revision `4b85a61`, together with 14 Angular tests and Compose/Keycloak regression. API tests seed delivery records rather than proving message consumption. See [API evidence](docs/PLAN.md#p4-notification-api-candidate).

The outbox relay is separately reviewed and locally/hosted accepted: [run 39165504508](https://github.com/kemselcuk/procureflow/actions/runs/39165504508), revision `5500316`, passed **156 combined backend tests**, **14 Angular tests** and six-service Compose/Keycloak regression. It publishes persistent events with mandatory routing, confirms, fenced leases and bounded retries/quarantine; consumer materialization and SMTP remain under review and are not integrated. See [relay evidence and limits](docs/PLAN.md#p4-relay-candidate-review-pending).

The notification-panel candidate is locally accepted against the real API and Keycloak: **78 Angular tests**, typecheck/build, actual browser login/read/token-refresh/logout and already-open-panel revocation checks passed. It uses explicitly seeded notification fixtures, not a running consumer; no broker-to-panel or email delivery is claimed. The published relay run's 14 Angular tests do not include this candidate. See [panel evidence and limits](docs/PLAN.md#panel-live-apioidc-evidence-review-bounded-acceptance).

## Project documents

- [Implementation plan and progress](docs/PLAN.md): scope, task owners, dependencies, acceptance criteria, estimates, risks and current progress.
- [Decision record](docs/DECISIONS.md): architectural reasoning, version research and important trade-offs.

Each subject has one authoritative document. This README is the entry point for running and evaluating the implemented foundation.

## Implemented foundation

- Java 21/Maven multi-module build: Spring Boot app, separate outbox-publishing worker and immutable schema-v1 event records. Relay is locally verified; consumption and SMTP are not implemented.
- Public system/OIDC configuration, OpenAPI and health endpoints; protected identity, company-membership, department and monthly-budget APIs; request lifecycle and department/finance approval with atomic budget checks, independently reviewed and locally verified.
- Flyway schema with tenant-composite constraints; separate runtime/migration/Keycloak database credentials; append-only request history, audit and outbox events. P3 adds immutable approval steps/charges and deferred settlement checks. Final usage, charge, state, history, audit and event/recipient capture share one transaction.
- V5 adds tenant-linked dispatch/inbox/delivery/notification/email-job tables, atomic dispatch initialization and a restricted worker database login. These are delivery foundations, not a running notification pipeline.
- Standalone Angular page with Keycloak Code+PKCE integration, membership status, loading/failure/retry states and component tests. Full product screens remain for P5.
- Pinned multi-stage containers and Compose, checked locally and on standard Ubuntu GitHub runners; a least-permission CI workflow repeats the foundation checks without deployment or paid storage.

## Intended product

Employees submit purchase requests. Department managers approve requests within their department; requests above EUR 1,000 additionally require a different finance approver. Company administrators manage departments, memberships and sample monthly budgets. A requester cannot approve their own request.

All company and department budgets are fictional application data. Approvals update database records and never initiate payments. The real infrastructure spending limit is documented separately in the [plan](docs/PLAN.md#cost-and-cloud-boundary).

The application will include company isolation, role-based authorization, request history, a budget overview, an approval inbox and notifications. The English interface will use a simple corporate design.

## Technology to purpose to evidence

Status is deliberately scoped: P0–P3 are accepted locally/hosted; notification recovery follows in P4. Observed runs and limitations are recorded in the [P0](docs/PLAN.md#p0-execution-evidence), [P1](docs/PLAN.md#p1-execution-record), [P2](docs/PLAN.md#p2-execution-record) and [P3 evidence](docs/PLAN.md#p3-execution-record). No performance, coverage or reliability result is claimed.

| Technology | Intended purpose | Code / configuration | Evidence / remaining gate |
| --- | --- | --- | --- |
| Java 21, Spring Boot, Maven | Modular monolith and separate notification worker | [Build](pom.xml), [app](backend/app), [worker](backend/notification-worker) | Java 21.0.12.1 builds; JPA usage/version and JDBC request/history/charge/audit/outbox rollback passed locally/hosted; see P3 evidence |
| Spring Security, Keycloak/OIDC | Login, validated JWTs and company-scoped permissions | [Identity](backend/app/src/main/java/com/procureflow/app/identity), [realm](infra/keycloak/procureflow-realm.json), [adapter boundary](frontend/src/app/authentication.ts) | [Security tests](backend/app/src/test/java/com/procureflow/app/IdentityAccessIT.java), [real-provider probe](scripts/oidc_smoke.py) and independent R1/R2 reviews passed; [P1 evidence](docs/PLAN.md#p1-execution-record) |
| JPA/Hibernate, PostgreSQL | Persistence, tenant relationships and concurrency control | [Budget mappings/charger](backend/app/src/main/java/com/procureflow/app/budgets), [access queries](backend/app/src/main/java/com/procureflow/app/identity/ActorAccess.java) | Real PostgreSQL 18.6; observed lock-barrier races for both final routes and administrator limit changes, plus post-flush failure rollback. Independent R1/R2 review and local/hosted gates passed |
| Flyway | Reproducible schema and constraints | [Migrations](backend/app/src/main/resources/db/migration), [foundation test](backend/app/src/test/java/com/procureflow/app/FoundationIT.java), [upgrade test](backend/app/src/test/java/com/procureflow/app/requests/RequestUpgradeIT.java), [V5 schema/privileges](backend/app/src/test/java/com/procureflow/app/NotificationDeliverySchemaIT.java) | V1–V5 migration/validation and V3→V4 compatibility passed locally/hosted. Data-preserving pre-worker V4→V5 role provisioning/rollback/backfill passed locally; [P4 scope and limitations](docs/PLAN.md#p4-execution-record) |
| Angular/TypeScript | Complete product UI (planned) | [Foundation/login page](frontend/src/app), [authentication tests](frontend/src/app/authentication.spec.ts) | Type checks, 14 component/service/interceptor tests and production build passed locally/hosted; real-browser product journeys remain planned |
| Transactional outbox; RabbitMQ | Capture committed events, then publish with recoverable leases | [Capture](backend/app/src/main/java/com/procureflow/app/requests/RequestEvents.java), [contract](backend/event-contracts/src/main/java/com/procureflow/events/RequestEvent.java), [relay](backend/notification-worker/src/main/java/com/procureflow/worker/relay) | Real PostgreSQL/RabbitMQ relay confirms/returns/nacks/timeouts/reclaim/restart tests passed locally; consumer deduplication, materialization and SMTP remain P4. [Scoped evidence](docs/PLAN.md#p4-relay-candidate-review-pending) |
| Mailpit | Inspect locally delivered demo email | Local development services | Expected messages visible after worker recovery; SMTP limitations documented |
| JUnit, Mockito, Testcontainers | Verify behavior at its real boundary | [HTTP](backend/app/src/test/java/com/procureflow/app/system/FoundationHttpBoundaryTest.java), [migrations/OpenAPI](backend/app/src/test/java/com/procureflow/app/FoundationIT.java), [security/database](backend/app/src/test/java/com/procureflow/app/IdentityAccessIT.java), [requests](backend/app/src/test/java/com/procureflow/app/RequestLifecycleIT.java), [approval/budget](backend/app/src/test/java/com/procureflow/app/ApprovalBudgetIT.java), [delivery schema](backend/app/src/test/java/com/procureflow/app/NotificationDeliverySchemaIT.java) | All **117 backend tests** passed together on [foundation hosted CI](https://github.com/kemselcuk/procureflow/actions/runs/39158792532), including 43 approval/budget and 11 delivery-schema/privilege cases; not broker/SMTP runtime evidence |
| Playwright | Browser-level checks of critical user journeys | `e2e/` | Login, submission, approval, denial and notification reports |
| Docker Compose | Reproduce the local foundation, later the complete product | [Compose](compose.yaml), [smoke check](scripts/check-foundation.py) | Five healthy services, API/OpenAPI/proxy/static, real Code+PKCE/request/replay/submit/reject/history/approval/budget checks, refresh/logout/revocation and isolated cleanup passed locally/hosted; not a complete-product claim |
| GitHub Actions | Repeat foundation/identity/request/approval checks; browser checks follow in P6 | [Workflow](.github/workflows/ci.yaml) | [Passing foundation run](https://github.com/kemselcuk/procureflow/actions/runs/39158792532): backend, frontend and isolated container/OIDC jobs; executable revision `adf11c3` |
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

This builds three application images plus the pinned PostgreSQL image with its initialization hook, and starts six services: app, PostgreSQL, frontend, worker, pinned Keycloak and RabbitMQ in a unique local project. Secrets are generated only in memory. It checks app/worker readiness, API/OpenAPI/static routing and durable broker topology, plus the real provider's authorization-code + S256 exchange, code replay denial, refresh/logout and membership revocation. The P2 journey creates/replays/submits a draft, reads manager history, rejects it and verifies scope denials. The P3 probe adds threshold approvals with separate actors, safe repeated-command conflicts and insufficient-budget denial; verification status is recorded separately. The OIDC probe drives real HTTP login forms/protocol calls, **not a rendered-browser test of the Angular adapter**. The checker removes **only its own test containers and database/broker volumes** in cleanup. It leaves images/build cache in Docker and writes `target/foundation-smoke.json` after success. Queue existence with zero consumers proves topology, not notification materialization or email delivery. Loopback ports are isolated; Keycloak/frontend ports are selected before realm import so issuer/redirect URLs remain exact.

For an interactive local/demo stack, set these eight environment variables using private terminal input or your local secret manager: `POSTGRES_PASSWORD`, `APP_DB_PASSWORD`, `MIGRATION_DB_PASSWORD`, `WORKER_DB_PASSWORD`, `RABBITMQ_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `DEMO_PASSWORD`. Use distinct local-only database/admin/broker passwords, not an account password; never paste them into chat, committed files or shared logs. `DEMO_PASSWORD` is the password you choose for the eight fictional demo identities. `.env.example` contains variable names only; no credentials are supplied by the repository. Keep the broker password stable when reusing its volume; changing an environment variable does not rotate an existing broker account.

```sh
docker compose up --build --wait
```

Open `http://localhost:4200`, `http://localhost:8080/v3/api-docs` and local Keycloak at `http://localhost:8180`. Default ports are 4200, 8080, 5432 and 8180; override `FRONTEND_PORT`, `APP_PORT`, `POSTGRES_PORT` or `KEYCLOAK_PORT` before startup if occupied. The frontend proxies API calls to the backend. `docker compose --profile worker up --build --wait` also starts RabbitMQ and the outbox relay. The worker exposes health on loopback port 8081, AMQP on 5672 and broker management on 15672; override `WORKER_PORT`, `RABBITMQ_AMQP_PORT` or `RABBITMQ_MANAGEMENT_PORT` if needed. Broker login is `procureflow` with your private `RABBITMQ_PASSWORD`. Persistent events intentionally accumulate in the durable queue until the consumer is implemented; no in-app materialization or email sending is claimed. App purchase operations do not connect to RabbitMQ.

The Compose configuration explicitly enables the `local-demo` application profile and imports a local-only Keycloak realm. Fictional accounts are `a-employee`, `a-manager`, `a-finance`, `a-admin` for Larkvane, and the corresponding `b-…` accounts for Northstar. Each company has engineering/operations departments. Business roles live in the application database, not in token role claims. The Keycloak administration account is `local-admin` with your `KEYCLOAK_ADMIN_PASSWORD`; application company administrators are not Keycloak administrators. Account/password management stays in Keycloak. Realm startup import skips an existing realm; changing environment passwords does not reset existing users.

P3 demo startup adds EUR 5,000 current-UTC-month budgets and four drafts per company: EUR 999.99, 1,000.00, 1,000.01 and 6,000.00. Submit/approve them through the API; the final amount exceeds the seed limit. Repeat startup inserts only missing fixtures and never overwrites edited drafts, limits or later revocations. New months receive no automatic approval-time budget; an administrator must configure one (demo startup may seed its current month).

Host application runs without `local-demo` do not seed company data. Unknown valid identities may self-register, but cannot access company endpoints until an administrator explicitly assigns an active membership. Company administration endpoints and exact scopes are in the [P1 matrix](docs/PLAN.md#p1-endpointaction-matrix). Administrative screens are still planned for P5; OpenAPI/raw API calls are the current interface.

Request API inputs/scopes are in the [P2 matrix](docs/PLAN.md#p2-endpointaction-and-retry-contract) and [P3 contract](docs/PLAN.md#p3--final-approval-and-budget-correctness). Create/submit require a UUID `Idempotency-Key`; identical retries preserve the original result/correlation ID, even if the resource has since changed. Follow with GET for current state. Edit/cancel/reject/approve use the last observed `version` and return 409 for stale commands. Approve takes no idempotency key; retrying a completed approval cannot charge twice. Final views expose `approvedAt` and UTC `budgetMonth`; `budget_missing`/`insufficient_budget` leave the request pending. A `retryable_conflict` means retry after the competing transaction settles, retaining the same create/submit key. Request forms/inbox screens remain planned.

Keep the same password environment when reusing the volume; do not regenerate it between restarts. `docker compose down` stops the development stack and preserves its databases. **Destructive local reset:** `docker compose down --volumes` deletes this project's application and Keycloak databases; use it only for data you intend to discard. Existing P0-only volumes do not contain the new separated database roles because PostgreSQL init scripts run only on an empty volume: migrate roles deliberately or reset only your disposable local data. Never silently delete a volume to bypass this prerequisite.

For frontend hot reload, keep the backend on port 8080 and run `npm start` from `frontend/` after stopping the frontend container; the development proxy forwards `/api/**` and OpenAPI to that backend. The service is loopback-only.

### Existing P1–P3 volume: add the worker login before V5

PostgreSQL initialization does not rerun on an existing volume. Keep all existing passwords unchanged, choose a new private `WORKER_DB_PASSWORD`, and provision the missing role before starting the V5 app. For the default Compose database, run the following **only when `procureflow_worker` does not already exist**; an existing role needs deliberate privilege/password inspection, not an automatic reset:

```sh
docker compose up -d postgres
docker compose exec -T postgres psql -U procureflow -d procureflow -v ON_ERROR_STOP=1 <<'SQL'
\getenv worker_password WORKER_DB_PASSWORD
\getenv app_database POSTGRES_DB
CREATE ROLE procureflow_worker LOGIN PASSWORD :'worker_password';
GRANT CONNECT ON DATABASE :"app_database" TO procureflow_worker;
GRANT USAGE ON SCHEMA public TO procureflow_worker;
SQL
docker compose up --build --wait
```

The quoted heredoc reads the password from the container environment rather than embedding a value in the command. Do not enable shell/SQL tracing or share credential-bearing output. V5 grants only the required delivery-table and metadata capabilities; it does not grant purchase/budget mutation or Keycloak access. If the role, database CONNECT or schema USAGE prerequisite is missing, V5 stops with an explicit error. No volume reset is required. Existing P0-only volumes also need the earlier P1 role separation before this step.

## Notification API

An active member can use `GET /api/notifications?page=0&size=20` and bodyless `POST /api/notifications/{id}/read`. The list exposes only event metadata; marking read preserves the first timestamp on retries. Another owner's or company's notification returns 404. `GET /api/admin/notifications/backlog` returns fixed own-company counts and requires the current company-administrator role. Authentication and membership are checked on every call; notification metadata does not grant access to purchase content. These endpoints do not themselves create notifications or send email.

## Product acceptance (planned)

The release evidence will cover three required scenarios:

1. Concurrent final approvals sharing one budget leave total usage within its limit and charge each approved request once.
2. Users cannot read or change another company's data or perform actions outside their role and department.
3. Notifications committed while the worker is stopped are completed after it restarts. Duplicate event delivery is safe for database effects; email delivery has the limitations recorded in the decision log.

The explicit local-demo profile contains two companies, multiple departments, distinct role holders and threshold/insufficient-budget draft fixtures. Notification recovery and rendered product journeys are not implemented yet.

Product architecture/transaction diagrams follow the real implementation during delivery. A demo video is not a deliverable.

Created in [T3 Code](https://t3.codes).
