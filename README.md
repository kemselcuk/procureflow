<img src="assets/icon.svg" width="64" height="64" alt="">

# ProcureFlow

ProcureFlow is a corporate purchase-request and approval application under development. It is a Java/Spring backend portfolio project with a planned complete Angular interface, intended to make engineering decisions understandable and verifiable in interviews.

**Current status: P0–P3 accepted; P4 locally verified, publication/hosted verification pending; remaining product screens planned.** Department/finance approval and atomic budget charging are implemented; see [P3 evidence and limitations](docs/PLAN.md#p3-execution-record). The integrated notification pipeline passed **237 backend tests**, **20 Python verifier tests** and eight real seven-service delivery/recovery cases locally. See [final P4 evidence and limits](docs/PLAN.md#final-p4-local-integration-gate).

The [P4 database foundation](docs/PLAN.md#p4-execution-record) is separately reviewed and locally/hosted verified: [run 39158792532](https://github.com/kemselcuk/procureflow/actions/runs/39158792532), revision `adf11c3`, passed **117 backend tests**, **14 Angular tests** and fresh Compose/Keycloak regression. A data-preserving V4→V5 database upgrade also passed locally. This is not full P4 acceptance; broker publication, notification APIs, consumption and SMTP delivery remain unimplemented in that revision.

The notification API is independently reviewed and locally/hosted verified with **133 backend tests**: own-notification list/read and company-admin backlog counts. [Hosted run 39161302004](https://github.com/kemselcuk/procureflow/actions/runs/39161302004) passed on revision `4b85a61`, together with 14 Angular tests and Compose/Keycloak regression. API tests seed delivery records rather than proving message consumption. See [API evidence](docs/PLAN.md#p4-notification-api-candidate).

The outbox relay is separately reviewed and locally/hosted accepted: [run 39165504508](https://github.com/kemselcuk/procureflow/actions/runs/39165504508), revision `5500316`, passed **156 combined backend tests**, **14 Angular tests** and six-service Compose/Keycloak regression. It publishes persistent events with mandatory routing, confirms, fenced leases and bounded retries/quarantine. Consumer materialization and SMTP are now integrated and independently locally verified; their hosted gate remains pending. See [relay evidence and limits](docs/PLAN.md#p4-relay-candidate-review-pending).

The notification panel is published as `c9b7d4f`: [hosted run 39183791303](https://github.com/kemselcuk/procureflow/actions/runs/39183791303) passed **156 backend + 78 Angular tests**, typecheck/build and six-service Compose/Keycloak regression. Separate local rendered-browser checks passed real API login/read/token-refresh/logout and already-open-panel revocation. They use explicitly seeded notification fixtures, not a running consumer; no broker-to-panel or email delivery is claimed. See [panel evidence and limits](docs/PLAN.md#panel-live-apioidc-evidence-review-bounded-acceptance).

## Project documents

- [Implementation plan and progress](docs/PLAN.md): scope, task owners, dependencies, acceptance criteria, estimates, risks and current progress.
- [Decision record](docs/DECISIONS.md): architectural reasoning, version research and important trade-offs.

Each subject has one authoritative document. This README is the entry point for running and evaluating the implemented foundation.

## Implemented foundation

- Java 21/Maven multi-module build: Spring Boot app, separate relay/consumer/SMTP worker and immutable schema-v1 event records. Database effects deduplicate; SMTP is at-least-once, not exactly-once.
- Public system/OIDC configuration, OpenAPI and health endpoints; protected identity, company-membership, department and monthly-budget APIs; request lifecycle and department/finance approval with atomic budget checks, independently reviewed and locally verified.
- Flyway schema with tenant-composite constraints; separate runtime/migration/Keycloak database credentials; append-only request history, audit and outbox events. P3 adds immutable approval steps/charges and deferred settlement checks. Final usage, charge, state, history, audit and event/recipient capture share one transaction.
- V5 adds tenant-linked dispatch/inbox/delivery/notification/email-job tables, atomic dispatch initialization and a restricted worker database login. The running pipeline revalidates membership before delivery and supports scoped privileged replay.
- Standalone Angular page with Keycloak Code+PKCE integration, membership status, loading/failure/retry states and component tests. Full product screens remain for P5.
- Pinned multi-stage containers and Compose, checked locally and on standard Ubuntu GitHub runners; a least-permission CI workflow repeats the foundation checks without deployment or paid storage.

## Intended product

Employees submit purchase requests. Department managers approve requests within their department; requests above EUR 1,000 additionally require a different finance approver. Company administrators manage departments, memberships and sample monthly budgets. A requester cannot approve their own request.

All company and department budgets are fictional application data. Approvals update database records and never initiate payments. The real infrastructure spending limit is documented separately in the [plan](docs/PLAN.md#cost-and-cloud-boundary).

The application will include company isolation, role-based authorization, request history, a budget overview, an approval inbox and notifications. The English interface will use a simple corporate design.

## Technology to purpose to evidence

Status is deliberately scoped: P0–P3 and the notification panel are accepted locally/hosted; the complete P4 pipeline is independently verified locally and awaits hosted acceptance. Observed runs and limitations are recorded in the [plan](docs/PLAN.md#final-p4-local-integration-gate). Recovery timings are small-fixture observations, not an SLA, performance benchmark or coverage claim.

| Technology | Intended purpose | Code / configuration | Evidence / remaining gate |
| --- | --- | --- | --- |
| Java 21, Spring Boot, Maven | Modular monolith and separate notification worker | [Build](pom.xml), [app](backend/app), [worker](backend/notification-worker) | Java 21.0.12.1 builds; JPA usage/version and JDBC request/history/charge/audit/outbox rollback passed locally/hosted; see P3 evidence |
| Spring Security, Keycloak/OIDC | Login, validated JWTs and company-scoped permissions | [Identity](backend/app/src/main/java/com/procureflow/app/identity), [realm](infra/keycloak/procureflow-realm.json), [adapter boundary](frontend/src/app/authentication.ts) | [Security tests](backend/app/src/test/java/com/procureflow/app/IdentityAccessIT.java), [real-provider probe](scripts/oidc_smoke.py) and independent R1/R2 reviews passed; [P1 evidence](docs/PLAN.md#p1-execution-record) |
| JPA/Hibernate, PostgreSQL | Persistence, tenant relationships and concurrency control | [Budget mappings/charger](backend/app/src/main/java/com/procureflow/app/budgets), [access queries](backend/app/src/main/java/com/procureflow/app/identity/ActorAccess.java) | Real PostgreSQL 18.6; observed lock-barrier races for both final routes and administrator limit changes, plus post-flush failure rollback. Independent R1/R2 review and local/hosted gates passed |
| Flyway | Reproducible schema and constraints | [Migrations](backend/app/src/main/resources/db/migration), [foundation test](backend/app/src/test/java/com/procureflow/app/FoundationIT.java), [upgrade test](backend/app/src/test/java/com/procureflow/app/requests/RequestUpgradeIT.java), [V5 schema/privileges](backend/app/src/test/java/com/procureflow/app/NotificationDeliverySchemaIT.java) | V1–V5 migration/validation and V3→V4 compatibility passed locally/hosted. Data-preserving pre-worker V4→V5 role provisioning/rollback/backfill passed locally; [P4 scope and limitations](docs/PLAN.md#p4-execution-record) |
| Angular/TypeScript | Login and notification panel; remaining product UI planned | [Frontend](frontend/src/app), [authentication tests](frontend/src/app/authentication.spec.ts) | 78 tests, typecheck/build passed on panel hosted CI; separate real-browser panel/OIDC checks passed locally. Full product journeys remain planned |
| Transactional outbox; RabbitMQ | Recoverable publication and deduplicated notification effects | [Capture](backend/app/src/main/java/com/procureflow/app/requests/RequestEvents.java), [relay](backend/notification-worker/src/main/java/com/procureflow/worker/relay), [consumer](backend/notification-worker/src/main/java/com/procureflow/worker/consumer) | Real PostgreSQL/RabbitMQ tests and stopped-worker/broker/poison/replay cases passed locally; [final P4 evidence](docs/PLAN.md#final-p4-local-integration-gate) |
| Mailpit | Inspect locally delivered demo email | [SMTP jobs](backend/notification-worker/src/main/java/com/procureflow/worker/email), [Compose](compose.yaml) | 16 SENT jobs correlated to captures across cases; real outage recovery passed locally. Acceptance-before-save can duplicate email; no external delivery claim |
| JUnit, Mockito, Testcontainers | Verify behavior at its real boundary | [HTTP](backend/app/src/test/java/com/procureflow/app/system/FoundationHttpBoundaryTest.java), [migrations/OpenAPI](backend/app/src/test/java/com/procureflow/app/FoundationIT.java), [security/database](backend/app/src/test/java/com/procureflow/app/IdentityAccessIT.java), [requests](backend/app/src/test/java/com/procureflow/app/RequestLifecycleIT.java), [approval/budget](backend/app/src/test/java/com/procureflow/app/ApprovalBudgetIT.java), [delivery schema](backend/app/src/test/java/com/procureflow/app/NotificationDeliverySchemaIT.java) | All **117 backend tests** passed together on [foundation hosted CI](https://github.com/kemselcuk/procureflow/actions/runs/39158792532), including 43 approval/budget and 11 delivery-schema/privilege cases; not broker/SMTP runtime evidence |
| Playwright | Planned repeatable browser checks of critical product journeys | P6 `e2e/` planned | Local panel browser evidence exists; a committed full-product browser suite is not yet implemented |
| Docker Compose | Reproduce the local application and messaging pipeline | [Compose](compose.yaml), [smoke check](scripts/check-foundation.py), [notification cases](scripts/notification_smoke.py) | Seven healthy services, real OIDC/business regression, eight notification cases and exact fixture cleanup passed locally; final pipeline hosted verification pending |
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

Maven rejects Java 25 rather than silently building with it. This session used a checksum-verified temporary Temurin 21.0.12.1+1 toolchain without replacing system Java. `verify` requires Docker and runs HTTP slice tests plus real PostgreSQL, RabbitMQ and Mailpit integration tests; `./mvnw test` alone does **not** prove database or delivery behavior. Reports are in each backend module's `target/surefire-reports/` and `target/failsafe-reports/` directories.

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

This builds three application images plus the pinned PostgreSQL image with its initialization hook, and starts seven services: app, PostgreSQL, frontend, worker, pinned Keycloak, RabbitMQ and Mailpit in a unique local project. Secrets are generated only in memory. It checks readiness, API/OpenAPI/static routing, durable broker topology with an active consumer and Mailpit, plus real authorization-code + S256, code replay denial, refresh/logout and membership revocation. Business checks exercise draft retries, submission/history/rejection, scoped access, threshold approvals, separate actors and budget denial. Eight notification cases cover stopped-worker backlog, duplicate replay, broker and SMTP outages, malformed quarantine, canonical terminal replay, recipient revocation and final sums/queue drain. The OIDC probe uses real HTTP forms/protocol calls, **not rendered Angular**. The checker removes **only its own test containers and database/broker volumes**, leaves images/build cache and writes `target/foundation-smoke.json`. Recovery bounds start at the recorded readiness observation; total elapsed fields are separate, not a 60-second outage-start SLA. Loopback ports are isolated; issuer/redirect URLs are selected before realm import.

For an interactive local/demo stack, set these eight environment variables using private terminal input or your local secret manager: `POSTGRES_PASSWORD`, `APP_DB_PASSWORD`, `MIGRATION_DB_PASSWORD`, `WORKER_DB_PASSWORD`, `RABBITMQ_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `DEMO_PASSWORD`. Use distinct local-only database/admin/broker passwords, not an account password; never paste them into chat, committed files or shared logs. `DEMO_PASSWORD` is the password you choose for the eight fictional demo identities. `.env.example` contains variable names only; no credentials are supplied by the repository. Keep the broker password stable when reusing its volume; changing an environment variable does not rotate an existing broker account.

```sh
docker compose up --build --wait
```

Open `http://localhost:4200`, `http://localhost:8080/v3/api-docs` and local Keycloak at `http://localhost:8180`. Default ports are 4200, 8080, 5432 and 8180; override `FRONTEND_PORT`, `APP_PORT`, `POSTGRES_PORT` or `KEYCLOAK_PORT` before startup if occupied. The frontend proxies API calls to the backend. `docker compose --profile worker up --build --wait` also starts RabbitMQ, Mailpit and the notification worker (relay, consumer and SMTP jobs). Worker health is on loopback port 8081, AMQP on 5672 and broker management on 15672; override `WORKER_PORT`, `RABBITMQ_AMQP_PORT` or `RABBITMQ_MANAGEMENT_PORT` if needed. Broker login is `procureflow` with your private `RABBITMQ_PASSWORD`. Mailpit's capture UI is `http://localhost:8025`, SMTP port 1025; override `MAILPIT_UI_PORT` or `MAILPIT_SMTP_PORT` if occupied. Mailpit has no local authentication, so both host ports stay loopback-only. It captures only fictional `<identity-UUID>@demo.invalid` recipients and discards its temporary mailbox when the container stops; durable job state lives in PostgreSQL. App purchase operations do not connect to RabbitMQ or SMTP. Combined end-to-end recovery passed independently locally; hosted verification remains pending, as recorded in the plan.

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

### Worker delivery and operator recovery

The worker publishes committed canonical events, consumes them into unique in-app notifications and initial email jobs, then sends those jobs to Mailpit. Consumer effects commit before broker acknowledgement; SMTP runs outside database transactions. A captured recipient whose membership is no longer active is skipped, not rerouted to another identity/company. The separate worker login cannot mutate purchases, budgets, business audit or immutable events. The business app does not need broker/SMTP availability to commit a request.

Inspect own-company counts through the admin backlog API. Detailed failure codes/fingerprints and scoped maintenance require a privileged local database operator, not merely a company-admin application account. The consumer stores sanitized diagnostic hashes/codes, never arbitrary message bodies; event-level diagnostics use the `IN_APP` channel convention. A transient `materialization_failed` diagnostic is terminal at the configured `worker.consumer.max-attempts` threshold (default 5); permanent validation codes are terminal immediately. An unknown malformed body's hash cannot reconstruct it for replay.

For a corrected event failure, first stop **only the worker in the intended development project**:

```sh
docker compose --profile worker stop notification-worker
docker compose exec postgres psql -U procureflow -d procureflow
```

Inspect the fixed failure code and correct its cause first; replay does not repair missing database privileges or SMTP availability. Copy the contents of the shared [event replay procedure](scripts/replay_notification_event.sql) into the maintenance session, replacing `<company-id>` and `<event-id>` with the explicit selected UUIDs **before execution**. The unchanged placeholders deliberately fail UUID parsing. The smoke and real database/broker regression execute this same SQL file rather than maintaining a second procedure in the documentation.

The row-locked procedure refuses missing/foreign-company rows, live leases and dispatch states other than `PUBLISHED`/`QUARANTINED`. It resets only the selected dispatch and its diagnostics. A valid canonical message can have an unscoped transient counter if canonical lookup previously failed: the procedure derives that exact counter from the immutable canonical publisher bytes and clears only its NULL-scoped `materialization_failed` fingerprint. Unrelated raw/malformed diagnostics remain untouched. A previously published event can be deliberately re-driven to recover consumer quarantine or verify deduplication; it is not an email resend command.

For a corrected SMTP failure, reset only the explicitly selected quarantined job instead:

```sql
BEGIN;
SELECT id, state, attempts FROM email_job
 WHERE id = '<job-id>'::uuid AND company_id = '<company-id>'::uuid FOR UPDATE;
UPDATE email_job
   SET state = 'PENDING', attempts = 0, next_attempt_at = now(),
       lease_token = NULL, lease_until = NULL, error_code = NULL
 WHERE id = '<job-id>'::uuid AND company_id = '<company-id>'::uuid AND state = 'QUARANTINED';
COMMIT;
```

Exit `psql`, then run `docker compose --profile worker start notification-worker`. Do not delete inbox, delivery, in-app or email rows to force a replay. Event/job IDs and first read times stay stable; `SENT`/`SKIPPED` jobs are not reopened. SMTP acceptance before the database save can still produce a duplicate on retry, including after explicit job replay; a stable Message-ID supports correlation but is not an exactly-once guarantee. The shared event replay procedure has independent component and full-stack evidence; SMTP job replay preserves the explicit company/job/state predicate. Detailed acceptance and limitations are recorded in the plan.

## Product acceptance (planned)

The release evidence will cover three required scenarios:

1. Concurrent final approvals sharing one budget leave total usage within its limit and charge each approved request once.
2. Users cannot read or change another company's data or perform actions outside their role and department.
3. Notifications committed while the worker is stopped are completed after it restarts. Duplicate event delivery is safe for database effects; email delivery has the limitations recorded in the decision log.

The explicit local-demo profile contains two companies, multiple departments, distinct role holders and threshold/insufficient-budget draft fixtures. Notification recovery and rendered product journeys are not implemented yet.

Product architecture/transaction diagrams follow the real implementation during delivery. A demo video is not a deliverable.

Created in [T3 Code](https://t3.codes).
