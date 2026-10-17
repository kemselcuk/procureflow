# ProcureFlow implementation plan

Status: **P0 and P1 accepted after independent review and hosted verification; P2–P7 pending**.

Prepared: 2026-10-17. Owner: main agent. This file is the single task/progress record. Architectural reasoning and version sources belong in [DECISIONS.md](DECISIONS.md); execution rules belong in [AGENTS.md](../AGENTS.md).

## Approval boundaries

- Discovery and the short approach were accepted. The user then requested this detailed plan.
- The detailed plan passed independent review and was delivered to the user before implementation approval.
- **Development gate opened:** on 2026-10-17 the user requested "uygulamaya başla" in the previous thread. Local implementation of this plan is authorized; execution resumed in the current thread after the previous run failed before making implementation changes.
- **Cloud gate:** real provisioning/deployment remains a separate future request and spending decision. No cloud account, server, domain or paid feature is required to complete the local project.

## Outcome and scope

Build a working English-language web application that demonstrates Java/Spring engineering suitable for discussing roles asking for approximately 3–4 years of experience. A completed project does not establish an equivalent employment history. The user must be able to explain its decisions and reproduce its behavior.

The release includes a modular monolith, a separate notification worker, Angular UI, example companies/users, OpenAPI, reproducible Docker Compose setup, automated tests, GitHub Actions and an architecture diagram in the README. A video is excluded by the user's later instruction.

### Product rules

- Four company-scoped roles: employee, department manager, finance approver and company administrator. An individual can hold multiple explicit role assignments, but the requester cannot approve their own request and the two approval steps must have different actors.
- An employee creates and edits their own draft, then submits a positive EUR amount with at most two decimal places, a department, title and justification. Use decimal arithmetic; there is no real purchase/payment integration.
- A request belongs to the requester's active assigned department when submitted; a client cannot select an unrelated department. Submission revalidates membership/department, and later membership changes never rewrite a submitted request. Amounts and budget values must fit the agreed database decimal range; reject excess precision or overflow rather than rounding silently.
- Amounts up to and including EUR 1,000 require department-manager approval. Larger amounts require department-manager approval followed by finance approval. The threshold is fixed initially.
- Submitted request fields are immutable. A requester can cancel a pending request; an authorized current-step approver can reject with a reason. Rejected/cancelled requests are terminal; corrections use a new draft. Approved requests cannot be cancelled in this release.
- States: draft, awaiting department approval, awaiting finance approval, approved, rejected, cancelled. Each transition has one allowed actor/action set; invalid or stale actions return an explicit conflict.
- Fictional department budgets use EUR and calendar months in UTC. Budget usage is charged to the month of **final approval**, using one server-side instant per command. Requests crossing a month boundary use the new month's budget; no reservation or automatic rollover exists.
- Final approval checks the current month's budget and changes usage, request state, approval history, audit history and outbox records in one database transaction. Missing/insufficient budgets prevent the approval and leave those business effects unchanged.
- Company administrators create departments, manage memberships for existing authenticated identities and set monthly limits. They cannot reduce a limit below already used funds or rewrite usage. They gain no implicit approval override. Keycloak account/password administration stays in Keycloak's local administration UI.
- Employees see their own requests/history; managers see their department's approval queue and permitted budget information; finance sees company finance work; company administrators see company configuration and audit information. A role matrix will turn these boundaries into explicit endpoint tests in P1.
- Two seeded companies and at least two departments per company provide distinct employee, manager, finance and administrator identities, threshold-boundary examples and an insufficient-budget example. Fixtures are repeatable and enabled only for local/demo use.
- Notifications cover submission/current approval assignments and terminal outcomes for the relevant recipients. Recipients, event types and in-app/email expectations are frozen as a contract in P2 before worker implementation.

### State and access contract

The following is the proposed contract to implement after approval. A held role grants only its explicit company/department scope; holding several roles never removes separation-of-duty checks.

| State | Allowed command and actor | Result |
| --- | --- | --- |
| Draft | Requester edits or submits their own request | Edit stays draft; valid submission awaits department approval |
| Awaiting department approval | Assigned department manager, excluding requester, approves or rejects; requester may cancel | Approval at or below EUR 1,000 is final; higher amounts await finance; reject/cancel is terminal |
| Awaiting finance approval | Company finance approver, excluding requester and the prior approver, approves or rejects; requester may cancel | Approval is final; reject/cancel is terminal |
| Approved, rejected or cancelled | Authorized readers view details/history | No further business transition; approved requests cannot be refunded or cancelled |

Final approval succeeds only when the budget check succeeds atomically; missing/insufficient budget leaves the current pending state unchanged. Stale/conflicting commands return a stable conflict code. Repeated successful approval must return the existing result or a documented conflict without duplicate effects. Other API retry semantics, including draft creation and submission, are frozen in P2.

| Role | Read scope | Business authority |
| --- | --- | --- |
| Employee | Own requests/timeline and own notifications | Create/edit/submit own drafts and cancel own pending requests |
| Department manager | Requests/timelines and budgets in assigned departments | Approve/reject the department step within that scope |
| Finance approver | Company finance queue, submitted-request timelines needed for finance work, and company budgets | Approve/reject the finance step |
| Company administrator | Company configuration and audit; no blanket access to other users' draft contents | Manage departments, memberships/role assignments and monthly budget limits; no implicit approval authority |

Every active member may read only their own notification records. Inactive membership denies application access even with an unexpired JWT. P1 turns this contract into an endpoint matrix; each later phase extends its negative tests when those endpoints exist.

### Interface scope

Login/logout, own request list with pagination/status filters, draft form, request details and timeline, approval inbox, monthly budget overview, company administration and an in-app notification list/read state. Include validation, loading, empty, forbidden and stale/conflict states. Prioritize keyboard access, labels, readable contrast and a usable narrow layout. Use standalone Angular components, typed forms, a small HTTP/auth boundary and feature-local state; introduce no global state framework without need.

### Outside this release

Real payments, supplier/ERP integration, invoices/tax calculation, attachments, multiple currencies, arbitrary approval-workflow builders, delegations, refunds, reservation accounting, cross-company user switching, public company signup, a platform-superuser portal, microservice decomposition, Kubernetes, Redis, commercial email services, HA promises, measured performance claims without measurements, a video and a live cloud deployment.

## Proposed architecture and ownership

These are future paths; this plan does not create the application tree.

- `backend/app/`: the main Spring Boot application. Package modules own identity/membership, requests, approvals, budgets, audit, notifications and outbox relay. Cross-module calls use explicit application services/contracts, not another module's repositories.
- `backend/event-contracts/`: the small versioned event envelope and payload types shared by the producer and worker. It contains no business repositories or application services.
- `backend/notification-worker/`: RabbitMQ consumption and notification/email-job processing. It mutates only notification/inbox/job tables. It does not approve requests or adjust budgets.
- `frontend/`: Angular application. Authentication uses the framework-independent Keycloak JavaScript adapter, Authorization Code + PKCE, and in-memory tokens.
- `e2e/`: critical Playwright journeys against the actual local application. Infrastructure failure tests remain backend/system tests rather than fragile browser timing tests.
- Local infrastructure: PostgreSQL, Keycloak, RabbitMQ and Mailpit plus application/worker/frontend containers. Keycloak gets its own database credentials/database; application schema changes use Flyway.
- Main agent owns shared build/configuration, SQL migrations, event/API contracts, Compose, CI and central documents. Writing subagents receive bounded feature/test paths only after contracts are agreed.

The [decision log](DECISIONS.md) specifies tenant boundaries, lock order, outbox recovery, SMTP limitations and the researched version baseline. PostgreSQL remains the source of truth; the API can serve purchase operations while RabbitMQ or the notification worker is unavailable.

## Owners and collaboration

| Owner | Responsibility | Model / access |
| --- | --- | --- |
| User | Product decisions; approval of the detailed plan and implementation start; future cloud/spending decision | Human |
| M — main agent | Architecture, shared contracts/files, main implementation, integration and final technical acceptance | `gpt-6.1-sol` |
| S — bounded specialist | Substantial independent implementation or test design when delegation helps | `gpt-6.1-sol` |
| D — routine specialist | Routine work and simple coding tasks with agreed contracts, including source collection, fixtures, components/tests or documentation checks | Existing OpenCode Go: `opencode-go/deepseek-v4.1-flash` or `opencode-go/glm-5.3-flash`, chosen as needed |
| R — independent reviewer | Review requirements, changed revision, tests and unresolved risks; owns no files | Separate `gpt-6.1-sol` context, not the change's author |

S, D and R are task roles using **at most two child-agent slots**, not three permanently running agents. No nested delegation. Separate worktrees for writers; read-only reviewers can inspect the main checkout. New worktrees cannot receive uncommitted planning changes, so a user-authorized committed baseline is a prerequisite. The main agent can implement sequentially until that prerequisite is met.

A task is accepted only after M has inspected its diff/output and actual evidence, resolved material findings and checked integration. Review status and evidence stay in this file. Small tasks do not justify a new agent.

Delegation transport, model-option selection and task boundaries are owned by the [working agreement](../AGENTS.md#responsibility-and-delegation). The routing-policy update did not itself open the development gate; the later explicit implementation request did.

## Phases, dependencies and acceptance

P0 and P1 are complete; P2–P7 are pending. Effort ranges are planning estimates of focused development, explanation and review time, not measured speed or model-runtime promises. The user can spend more than 10 hours weekly; actual sequencing will adapt to the observed pace, especially Angular learning. Total estimated effort is approximately **90–140 hours**, without a committed completion date.

### P0 — Reproducible foundation and compatibility gate

Depends on: explicit development approval. Owner: M; D may check bounded tooling/fixture details. Estimate: 6–10 hours.

Current: P0.1–P0.4 complete. M accepted the phase after local checks, independent R1/R2 reviews and all three jobs passing on [hosted run 39049180018](https://github.com/kemselcuk/procureflow/actions/runs/39049180018), exact revision `57d43622873d05625a13f5d20cce1ad7efbe660f`. P1 subsequently started as recorded below.

- P0.1: establish Maven wrapper/multi-module build, Java 21 toolchain/enforcement, minimal Angular application, first container builds and repeatable development commands.
- P0.2: resolve the [version research risks](DECISIONS.md#d002--version-baseline-and-compatibility-gates). Pin exact package/image versions and lockfiles. Inspect the actual dependency tree, including Springdoc/Jackson; choose a compatible security-patched set before accepting the baseline. Recheck primary sources if releases have changed.
- P0.3: introduce Flyway with the PostgreSQL-specific module, JPA schema validation and PostgreSQL Testcontainers; keep demo fixtures separate from production migrations.
- P0.4: establish CI with a Java 21 build, one meaningful PostgreSQL migration test, frontend type/build checks and workflow permissions restricted to their purpose. Do not enable paid runner features.

Exit: Java compiler, Maven execution and CI all demonstrably use Java 21; resolved dependencies have no unresolved known relevant high-severity issue; `/v3/api-docs` starts and serializes a small real endpoint correctly; empty database migration and basic container readiness pass. Record exact revisions/commands and image tags/digests. Docker daemon availability is verified after approval, not assumed from the installed CLI.

### P1 — Identity, company boundary and database integrity

Depends on: P0. Owner: M/S; independent R review of authorization and schema. Estimate: 12–18 hours.

Started on the user's "devam et" after P0 acceptance; baseline `023d736`. P1.1–P1.4 and the exit gate are complete after real-database/token checks, independent R1/R2 review and all three hosted jobs passing on [run 39074281305](https://github.com/kemselcuk/procureflow/actions/runs/39074281305), executable revision `92688d5a5e619585d246ac2fac1565739f878493`. M's final acceptance and limitations are in the [execution record](#p1-execution-record).

#### P1 endpoint/action matrix

All business routes require a signature/issuer/audience/expiry/subject-validated bearer token and a current active database membership. Company scope never comes from a body, header or caller-selected company. A revoked membership/role takes effect on subsequent calls without waiting for JWT expiry. Foreign-company resource lookup returns 404; absent/invalid JWT 401; membership/role denial 403. Unknown business endpoints remain denied or absent, not implemented placeholders.

| Endpoint | Authority / scope |
| --- | --- |
| `POST /api/identities/self` | Valid JWT only; idempotently registers **only the caller's** issuer+subject, returns that identity's ID. This authentication bootstrap is the sole membership-free identity operation, not access to company data |
| `GET /api/auth/config` | Public non-secret issuer/client-ID configuration for the local SPA; no token, account or company data |
| `GET /api/me` | Any active member; own company, primary department, explicit roles and managed department IDs |
| `GET /api/departments` | Employee: own department; manager: explicitly assigned departments; finance/admin: own company; union of held scopes |
| `POST /api/admin/departments` | Company administrator; creates only in the actor's validated company |
| `GET /api/admin/memberships` | Company administrator; own-company members only, no global identity directory |
| `POST /api/admin/memberships` | Company administrator; links a previously self-registered identity to an own-company department and explicit roles. Another active company membership yields a generic conflict |
| `PUT /api/admin/memberships/{id}` | Company administrator; own-company target, version checked; update department/active status/roles/manager assignments, preserve at least one active company administrator |
| `GET /api/budgets?month=YYYY-MM` | Manager: assigned departments; finance/admin: own company; employee alone denied |
| `PUT /api/admin/departments/{id}/budgets/{month}` | Company administrator; own-company department, nonnegative EUR limit with at most two decimals, never below usage; no client-supplied usage |

No arbitrary identity/company creation or password administration is added to the business API. Keycloak remains the account/password owner. Request/history/notification endpoints and their matrices are extended in P2–P4, not claimed by P1.

- P1.1: local Keycloak realm/public SPA client, strict redirect/CORS configuration, resource-server JWT validation and issuer/audience rules. Use real OIDC for at least one integration journey.
- P1.2: identities keyed by issuer + subject; one active company membership per user in this release, scoped roles and manager-department assignments. Freeze the endpoint/action role matrix.
- P1.3: tenant-aware repositories and composite relationships; seed two companies and distinct actors. Add company predicates to lists, details, mutations, history and notification reads. IDs/headers supplied by callers never establish company authority.
- P1.4: company/department/monthly-budget administration and a minimal `/me` response for the UI. Protect membership changes; do not let administrators grant themselves an approval bypass.

Exit: the P1 subset of AC-SEC/AC-DB passes for identity, membership, department and budget endpoints against the real database; cross-company foreign-key attempts fail; real tokens are validated and wrong issuer/audience/signature/expiry or inactive membership are rejected. Future request/history/notification endpoints are tested in their owning phases, and the complete matrix is required at P6. R's material security findings must be resolved before P1 acceptance; an unresolved finding blocks the phase and is reported to the user.

### P2 — Request lifecycle, history and contracts

Depends on: P1. Owner: M/S; D may own agreed fixtures or independent API tests. Estimate: 10–16 hours.

- P2.1: draft/create/edit/submit/list/detail/cancel/reject commands with validation, pagination and explicit errors. Reject mutation of submitted amounts/departments, department spoofing, decimal overflow and excess precision; define retry semantics for create/submit before the UI depends on them.
- P2.2: allowed transition policy and immutable audit records carrying actor, company, action, timestamp and correlation identifier. Never log tokens or unnecessary request bodies.
- P2.3: define OpenAPI resources/error codes and event envelope: stable event ID, version, company, request ID, occurred-at timestamp and recipient references. Define notification recipients and expected channels for each transition.
- P2.4: establish state/audit/outbox persistence in one transaction from the first notification-producing command; publishing is introduced in P4. Add rollback and API retry/idempotency tests.

Exit: the implemented draft/submission/cancel/reject state/role matrix, amount validation, cross-company history reads and state/audit/outbox rollback tests pass. Approval threshold routes are specified here and exercised end to end in P3. API and event contracts are reviewed and stable enough for parallel UI/worker work. Outbox entries can accumulate safely without a broker.

### P3 — Final approval and budget correctness

Depends on: P2. Owner: M/S; a different S/R designs or reviews race tests. Estimate: 12–18 hours.

- P3.1: department/finance steps, separation of duties, stale-action handling and one database transaction for the final approval.
- P3.2: lock the request first, then the company/department/current-month budget row; recheck state and authorization, validate available funds, update usage and write approval/audit/outbox records. Use one lock order across commands.
- P3.3: database checks for positive amounts, `0 <= used <= limit`, unique approval steps and unique final budget charge per request. Replay of the same command must not create another charge or event.
- P3.4: real concurrent-transaction tests using independent connections and synchronization barriers. Include same-budget/different-request races on both approval routes, same-request double approval, approval versus cancellation/rejection or a budget-limit change, insufficient funds and month-boundary/missing-budget cases.

Exit: AC-BUDGET, AC-REPLAY, AC-TX, AC-FLOW and AC-MONTH plus the approval/budget portions of AC-SEC/AC-DB pass without relying on an in-memory lock, a mocked repository, sequential API calls or the JUnit test's outer rollback transaction. Record expected winners/conflicts and persisted amounts/events, not just HTTP responses.

### P4 — Reliable notifications and recovery

Depends on: P2 event contract and P3 accepted final-approval events. Owner: M/S; D may implement bounded notification display/API tests. Estimate: 16–24 hours.

- P4.1: outbox claim/lease/retry/recovery, persistent broker publication, mandatory routing and publisher confirms. Mark published only after successful routing/confirmation; recover a crashed relay's lease.
- P4.2: durable queue/exchange and explicit consumer acknowledgement after the inbox/notification/email-job transaction commits. Deduplicate by event/recipient/channel and validate tenant-linked recipients.
- P4.3: separate leased SMTP jobs within the worker; Mailpit destination, bounded retry/backoff, failure status/dead-letter handling and an authenticated/manual replay operation or documented operator command. Never hot-loop a poison message.
- P4.4: in-app notification list/read state, outbox/job backlog visibility and recovery instructions. Preserve purchases while messaging dependencies are down.

Exit: AC-NOTIFY and AC-OUTAGE plus notification tenant/recipient denial tests pass with real RabbitMQ, PostgreSQL and Mailpit. Demonstrate broker outage, relay crash/redelivery, worker stopped during event creation and restart, transient SMTP failure and poison-message quarantine/replay. Document the SMTP acceptance-before-state-save duplicate window; do not claim end-to-end exactly-once email.

### P5 — Complete Angular product

Depends on: P2 API contract; can start alongside P3/P4 after P1 login is stable. Final acceptance depends on P3 and P4. Owner: M/S, with D for bounded routine screens after interfaces are agreed. Estimate: 16–24 hours.

- P5.1: Keycloak login/logout, in-memory token lifecycle, safe API interceptor and route guards. Backend authorization remains authoritative.
- P5.2: own request list/form/detail/timeline and approval inbox, including denied/stale/insufficient-budget responses and prevention of accidental repeat submission.
- P5.3: budget/admin screens and in-app notifications. Display the fictional budget's charging month and the two-step rule clearly.
- P5.4: meaningful component tests for form/errors/role visibility, plus keyboard and narrow-layout checks. Explain typed forms, component boundaries, services, asynchronous data and state choices to the user in Turkish.

Exit: every agreed screen works with the real API; errors preserve user input where appropriate; loading/empty/forbidden states are usable. The user can explain one form, one HTTP/auth interaction and one state-management choice.

### P6 — System acceptance and continuous verification

Depends on: P3, P4 and P5. Owner: M with independent R; S can own bounded system tests. Estimate: 12–18 hours.

- P6.1: Playwright login and employee-to-manager-to-finance journeys with actual role holders; cross-company/direct-URL denial and notification visibility.
- P6.2: reproducible fault/recovery scenario commands using the local stack. Persist test reports and correlation/event IDs; do not require a video.
- P6.3: GitHub Actions jobs for backend unit/integration tests, frontend checks/build and a critical browser suite using local containers on a hosted Linux runner. Seed deterministically; wait for health, not arbitrary sleeps; clean up resources after failure.
- P6.4: review API errors, secret handling, migrations, token/CORS behavior, dependencies and container configuration. Fix material findings and rerun affected acceptance checks.

Exit: all acceptance cases below pass for one reviewed revision; CI evidence is attached when a repository/runner entitlement is available. If GitHub hosting is not yet available, mark CI unverified and keep that item open rather than claiming delivery.

### P7 — Portfolio evidence and cloud readiness

Depends on: P6. Owner: M; R validates README claims against actual files/reports. Estimate: 6–10 hours.

- P7.1: verify documented local setup from a fresh checkout; record prerequisites, URLs, demo identities, reset instructions and known limitations. Keep destructive local resets explicitly labelled.
- P7.2: replace the README's planned evidence cells with real links and commands; add architecture and transaction/message-flow diagrams. Explain trade-offs in the decision log and include short interview exercises.
- P7.3: stateless application configuration, external secrets, persistent volumes, health/readiness, shutdown/retry behavior and environment-specific OIDC/proxy URLs. Verify same-site frontend/API routing where possible and document TLS/backup requirements for a future public environment.
- P7.4: prepare the cloud decision/deployment checklist within existing documents. Provider-specific Terraform, validation and actual provisioning are deferred until the user selects a target; no placeholder Terraform is presented as validated infrastructure.

Exit: local product and evidence index are honest and reproducible; no required acceptance case is pending; diagrams describe the implementation that exists. Cloud deployment is explicitly unperformed. The user can explain tenant scope, budget locking, outbox redelivery and the main Angular flow.

## Critical path and parallel work

P0 → P1 → P2 → P3 → P4 → P6 → P7 is the backend/reliability path. P5 branches from P2 and rejoins at P6; its final notification/budget UI waits for P3/P4 behavior. UI and worker work can run in two independent writing tasks only after their contracts and path ownership are fixed. Shared migrations/contracts/build files remain sequential main-agent work. Independent review uses an available slot and must finish before the affected phase exits.

## Acceptance catalog

These are required future observations, not passing results. Use a fixed Clock/fixture period and reset database state between cases.

| ID | Scenario | Observable acceptance |
| --- | --- | --- |
| AC-BUDGET | Same company/department/month, different requests: department-final path has two EUR 700 requests against EUR 1,000 remaining; finance-final path has two EUR 1,200 requests with prior department approval against EUR 2,000 remaining | Exactly one final approval succeeds in each case; the other receives insufficient-budget conflict. Usage increases by EUR 700 or EUR 1,200 respectively, with one charge and no negative remaining funds. Concurrent budget-limit edits also preserve `0 <= used <= limit` |
| AC-REPLAY | Same final approval concurrently/repeatedly submitted | At most one approval step, charge and transition event; a replay returns the recorded result or a documented conflict without changing usage |
| AC-TX | Failure after budget mutation but before audit/outbox completion | Entire command rolls back: usage, state, approval, charge, audit and outbox remain consistent; no partial approval |
| AC-SEC | Company B identity targets company A list/detail/action/budget/audit/notification; spoofed company/department; wrong role/department/self approval/same actor on both steps; revoked/inactive membership or role | Server denies every unauthorized operation; no data leakage or state change. No token returns 401, role/membership failures 403, foreign-company resource lookup 404. Invalid JWT signature/issuer/audience/expiry returns 401. Lists expose only the permitted scope, and a still-valid JWT does not bypass membership/role changes |
| AC-DB | Cross-company relationships inserted; budget below usage; duplicate approval/charge | PostgreSQL constraints reject invalid writes independently of UI validation |
| AC-FLOW | Amounts EUR 999.99, 1,000.00 and 1,000.01; zero/negative/overflow/excess-precision inputs; cancel/reject/stale actions and approval/cancel races | Correct one/two-step route, distinct actors, immutable submitted fields, accurate timeline and no budget charge before the final step. Invalid amounts are rejected without rounding. Exactly one conflicting transition commits; a cancelled/rejected request has no charge, while an approved request has one |
| AC-MONTH | Pending request approved across UTC month boundary; missing next-month budget | Correct month charged using the command's captured instant; missing budget leaves approval pending and no effects committed |
| AC-NOTIFY | Stop worker; create a known set of committed events; restart it without resetting broker/database | Every expected in-app notification and email job reaches completion within a fixture-defined 60-second recovery bound; correlate IDs with Mailpit observations. In-app effects remain unique on redelivery; ambiguous SMTP retry may duplicate email and is documented |
| AC-OUTAGE | Broker unavailable; relay crashes after broker confirm before marking published; temporary SMTP failure; poison message | Purchase commits with pending outbox; relay/worker recover; database effects deduplicate; retries are bounded/backed off; poison events are visible and replayable after correction |
| AC-WEB | Actual employee/manager/finance/admin sessions plus foreign-company direct URL | Real login and permitted flows work; errors/denials are understandable; browser tests assert backend outcomes rather than hidden buttons alone |
| AC-REPRO | Fresh checkout and empty local databases | Verified Compose/setup/migration/seed instructions reproduce the product and tests; demo fixtures are absent from non-demo configuration |

The 60-second recovery bound is a proposed acceptance deadline under a small local fixture, not a measured SLA or throughput claim. Adjust it only with evidence and a recorded explanation.

## Evidence and completion protocol

For each material task, record: task ID, owner/model, reviewed revision/diff, relevant decisions, executed commands, observed output/report location, findings/fixes, remaining limitations and M's acceptance. CI run URLs and report files become README evidence only after they exist. Normal generated build/test reports need no additional hand-written Markdown documents.

A phase is complete when its exit conditions pass and M accepts the integration. A model's final message is insufficient. At a meaningful checkpoint, explain the decision and alternative in Turkish and give the user a short interview question or exercise. No percentage coverage, latency, availability or hiring outcome is promised.

## Cost and cloud boundary

- Additional service and model/API allowance now: **EUR 0**. Use existing Codex/OpenCode Go access; no paid fallback when a model quota fails.
- Infrastructure reserve: **EUR 10–20 total for all five portfolio projects**, not monthly and not per project. Projects may be hosted one after another, at the user's request.
- Local Compose, Mailpit and existing tools are the starting route. Confirm applicable local container-runtime licensing and GitHub Actions entitlement before any option that requires a paid plan; do not assume free hosted minutes for an unspecified private repository.
- Provider-neutral application/container preparation is included. No cloud account, domain, registry subscription, managed database or provider resource is purchased or provisioned now.
- A future deployment decision compares an EU-region VPS (for example Hetzner), an AWS/Azure/GCP VM and any genuinely suitable free allowance using then-current official prices. Include VAT, storage, snapshots, IPv4, transfer and minimum billing; do not rely on expiring credits or claim that a provider fits before pricing it.
- Once a provider is selected, define Terraform resources/state/secrets, a cost cap, lifetime and deletion verification. `fmt`/`validate` is configuration evidence; authenticated `plan` and `apply` are separate operations. Actual deployment/cleanup and spend require the user's future deployment instruction within an explicit allowance.
- A local, tested cloud-ready application is the release goal. It is not labelled production-deployed or production-hardened until that work is performed.

## Risks and prerequisites

| Risk / open item | Handling and owner |
| --- | --- |
| System Maven uses Java 25 | Project wrapper/compiler/enforcer, local runs and passing hosted CI use Temurin 21.0.12.1; negative test rejects JDK 25. System tools were not replaced |
| Springdoc/Jackson transitive version/security report | Both Jackson families patched on Boot's minor lines; resolved tree and actual OpenAPI/schema/HTTP serialization tests passed. Same-line Tomcat security override added after source/OSV review; see D002. Continue reassessing on Boot updates |
| Reproducibility and compatibility scope | Foundation and P1 dependencies/lockfile/base images are pinned and checked locally and on a clean hosted Linux checkout, including the real Keycloak protocol journey. RabbitMQ/Mailpit/browser binaries remain pending in their owning phases; complete-product fresh-machine reproduction is not yet claimed |
| Docker engine access | Initially unavailable; user answered "açtım". Docker Desktop 4.93.0 / Engine 29.8.1 access verified. Local disposable PostgreSQL and Compose checks ran; the agent did not start/install the runtime |
| Terraform executable absent | No Terraform installation now; future provider work includes selecting/installing a suitable version with user-authorized setup |
| GitHub publication and access | Public `kemselcuk/procureflow` created with explicit user approval; origin push/fetch and standard free Linux CI verified. No paid storage, branch-policy guarantees or cloud deployment are claimed; T3 metadata refresh remains a UI/app limitation |
| SMTP cannot make a generic database transaction atomic with external acceptance | Durable jobs/retry plus truthful duplicate-window documentation; no end-to-end exactly-once promise |
| Two isolated writers need a committed baseline and disciplined shared-file ownership | M coordinates the baseline and contracts; work sequentially if prerequisites are absent |
| Portfolio scope or Angular learning takes longer than estimated | Prefer the agreed core; adjust estimates from observed work and ask before expanding product scope |

## Progress and review record

| Date | Work | Status / evidence |
| --- | --- | --- |
| 2026-10-17 | Discovery choices and approach | Accepted in conversation; video removed; real cloud deferred; model/service extra spending EUR 0 |
| 2026-10-17 | Repository and local tools inspected | Only existing README/icon; clean `main`, initial commit `5e51f26`; Java 21.0.8; Maven 3.9.11 running Java 25; Node 22.23.2; npm 10.9.8; Docker CLI 29.8.1; Compose 5.5.1; Terraform not on PATH. No engine/startup test |
| 2026-10-17 | Detailed plan and central documents | Initial draft prepared in the previous thread; resumed validation and independent review are recorded below |
| 2026-10-17 | Delegation policy updated at the user's request | Added GLM-5.3-Flash alongside DeepSeek V4.1 Flash for routine/simple work as needed; all child work routed through T3. Live `orchestrator_capabilities` advertises both exact OpenCode Go model IDs and `low`/`high`/`max` variants on provider `opencode`; catalog evidence only, no model execution or application checks. Implementation remains pending |
| 2026-10-17 | Delegation-policy document verification | Main agent reviewed the updated sections across AGENTS/PLAN/DECISIONS in the uncommitted working tree based on `5e51f26`. `git diff --no-index --check /dev/null <file>` for each document emitted no whitespace diagnostics; exit 1 reflects file differences. This is a scoped document check, not the pending independent plan review |
| 2026-10-17 | Planning resumed from the failed thread | Accepted choices and completed DeepSeek source collection recovered from the previous thread. M corrected phase acceptance ordering and finance-route budget fixtures, added the proposed state/access contract, and retained documentation-only authorization |
| 2026-10-17 | GLM source collection and main-agent validation | T3 task `node:delegated-task:command%3Amcp%3A01d53088-c1e4-455c-b844-b0a6c7305d1b%3Adelegate-task%3Aprocureflow-plan-resume-source-glm-20261017-r1`, provider `opencode`, model `opencode-go/glm-5.3-flash`, requested variant `high`. M independently fetched publisher npm metadata with Python `urllib`, official PostgreSQL/Keycloak/RabbitMQ pages, and Mailpit release metadata with `gh api`; D002 contains accepted facts/URLs. The suggested RxJS 7.4.x pin was not adopted: the peer range permits later 7.x patches. No installation, dependency resolution, container pull or runtime test |
| 2026-10-17 | Independent Sol planning review R1 | T3 task `node:delegated-task:command%3Amcp%3A01d53088-c1e4-455c-b844-b0a6c7305d1b%3Adelegate-task%3Aprocureflow-plan-sol-review-20261017-r1`; [review report](t3-thread://v1/thread:delegated-task:command%3Amcp%3A01d53088-c1e4-455c-b844-b0a6c7305d1b%3Adelegate-task%3Aprocureflow-plan-sol-review-20261017-r1). Provider `codex`, model `gpt-6.1-sol`, requested reasoning `xhigh`. All four document SHA-256 values matched the reviewed snapshot on base `5e51f26` (full hashes in report). No material findings; no unresolved review objections. M read the report, independently validated material source facts and accepted the planning deliverable. Reviewer did not refetch sources or run application checks |
| 2026-10-17 | Planning document checks | M's read-only Python document checker passed relative links/anchors, final newlines, whitespace and fenced-block balance across all four files; `git diff --check` exited 0. No application/runtime checks were run. Subsequent changes only record completion/review evidence and README status; M reviews those changes directly |
| 2026-10-17 | User approval of detailed plan and implementation start | Explicit "uygulamaya başla" recovered from [previous thread](t3-thread://v1/73ffbc12-4dca-4038-92a9-52ebd2fd2430), message `ac7eb991-08b1-417f-af8e-c84e87fff689` at 14:56:50 UTC, after plan delivery. Previous run failed before implementation. Current thread starts P0; no commit/push, paid service or deployment approval inferred |

### P0 execution evidence

Task: `P0-local-r1`, owner M (`openai/gpt-6.1-sol` via OpenCode), uncommitted working tree based on `5e51f26`. No implementation commit exists. Relevant decisions: D001, D002, D007–D010. The independently reviewed executable snapshot contains 48 files in `target/p0-review-manifest.json`, manifest SHA-256 `bd56ca8fb70630cda36d5cc2afd055e7f005598abd126e56df6f79d6f435f646`. All source/config hashes still matched at final acceptance. The four central documents also matched their handoff hashes during review; subsequent documentation-only evidence updates are reviewed separately by M.

| Check / command | Observed result and evidence | Limitation |
| --- | --- | --- |
| `./mvnw --version`, explicit session-temporary `JAVA_HOME` | Maven 3.9.11, Temurin 21.0.12.1+1; both compiler/test runtime use Java 21. Separate `JAVA_HOME=<installed JDK25> ./mvnw validate` negative check returned nonzero with the expected enforcer message | System Maven/JDK unchanged; a user-selected maintained JDK 21 is required outside containers |
| `./mvnw --batch-mode verify dependency:tree -DoutputType=json -DoutputFile=target/dependency-tree.json` | Passed: 3 HTTP boundary tests + 2 PostgreSQL integration tests, zero failures/errors/skips. Reports: `backend/app/target/surefire-reports/`, `backend/app/target/failsafe-reports/`; dependency trees in each module's `target/` | Local macOS arm64 only. Mockito dynamic-agent warning remains test-only; no business-policy tests exist yet |
| Real PostgreSQL 18.6, Flyway 12.4.0, Hibernate validation and real HTTP | Empty database migrated to V1; validation/no-op remigration passed; duplicate slug and blank company name rejected by PostgreSQL. Full Spring Boot app served the documented endpoint and OpenAPI schema plus database readiness | Foundation only; not tenant isolation, budget concurrency or a migration upgrade between product versions |
| Resolved dependency graph + primary advisory/OSV check | Both Jackson families resolved to 2.21.7/3.1.7; Tomcat family to 11.0.27. OSV `/v1/querybatch` checked 156 distinct resolved Maven coordinates/versions after fixes, no reported findings. Report: `target/osv-report.json`; D002 records primary advisory rationale | Point-in-time package-database query, not exhaustive assessment or an OS-image scan |
| `npm ci`, `npm run typecheck`, `npm test`, `npm run build`, `npm audit --audit-level=high` from `frontend/` | All passed; 3 Vitest component tests; no reported npm vulnerabilities. Exact direct dependencies and a resolved lockfile. Production output in `frontend/dist/procureflow-web/` | Host used Node 22.23.2/npm 10.9.8; pinned container/CI Node is 22.23.3. These are component tests, not real-browser product acceptance |
| `python3 scripts/check-foundation.py` | Passed: three images built; all four services healthy; app readiness, proxied metadata/OpenAPI, static page, Java runtime and nginx syntax checked. Report: `target/foundation-smoke.json`. Unique test project and its volume removed successfully | Initial fixed-port attempt hit a 5432 bind conflict; script now requests random loopback ports and discovers mappings. No unrelated container was stopped. Worker readiness is process-only |
| `actionlint .github/workflows/ci.yaml` (publisher-checksummed 1.7.12) | Passed locally; action versions pinned to full publisher tag commit hashes; workflow has read-only repository permissions and no deployment/publish step | Initially local-only; hosted verification is now recorded below. Evidence is retained in run logs, not uploaded artifacts |
| `npm start -- --port <free local port>` | Angular development server served the application shell over loopback; its process group was stopped after the check | HTTP/dev-server smoke only; not a browser-rendering or product-journey test |

Resolved early failures: Testcontainers 2.0.5 rejected a tag+digest reference before startup; using the identical PostgreSQL digest without the tag resolved parsing. The first fresh container build correctly rejected a distribution checksum mismatch because the `only-script` wrapper switches ZIP to TAR when `unzip` is absent. Regenerated official wrapper type `script` pins both bootstrap-JAR and ZIP SHA-256, so Java-based extraction is consistent across macOS/Linux without weakening validation. Clean container downloads then passed. Initial Spring-generated development password logging was removed by excluding the unused default user service; subsequent test runs do not generate that password.

Research: T3 task `node:delegated-task:command%3Amcp%3A9dade2b4-53f8-4289-b56f-2a1a57976b52%3Adelegate-task%3Aprocureflow-p0-dependency-glm-20261017-r1`, provider `opencode`, model `opencode-go/glm-5.3-flash`. M independently read the Boot POM/Maven metadata, Jackson primary advisory and Tomcat security page before accepting the same-line security overrides; the actual graph, runtime tests and package queries above are the acceptance evidence, not the research report alone.

Independent review R1: T3 task `node:delegated-task:command%3Amcp%3A9dade2b4-53f8-4289-b56f-2a1a57976b52%3Adelegate-task%3Aprocureflow-p0-sol-review-20261017-r1`; [review report](t3-thread://v1/thread:delegated-task:command%3Amcp%3A9dade2b4-53f8-4289-b56f-2a1a57976b52%3Adelegate-task%3Aprocureflow-p0-sol-review-20261017-r1), provider `opencode`, model `openai/gpt-6.1-sol`. Reviewer confirmed the 48-file manifest, four document hashes, source/config scope and existing backend reports; no material findings or unresolved review objections. Reviewer ran no builds/tests/containers and did not renew security scans. T3 saved configuration for both research and review confirms requested variant `high`; this is not a provider-side reasoning attestation.

M's disposition: accepted the **local foundation slice**, after reading the report and independently inspecting source/output. The reviewer's requested final `-f compose.yaml` smoke rerun was already completed during review against the exact reviewed executable snapshot: project `procureflow-check-fd647d11aaca`, four healthy services and all four HTTP checks passed; Java/nginx checks passed. A subsequent Docker label query confirmed no remaining containers or volumes for that test project. No corrective code diff was required. Final read-only Python checks passed all four documents' relative links/anchors, whitespace, final newlines and fenced blocks; `git diff --check` passed; all executable hashes remained unchanged. M directly reviewed this documentation-only result/status update; application suites were not unnecessarily rerun.

At local-slice acceptance, hosted CI verification remained open and no Git remote or publication authorization existed. P1 had not started; no cloud resources, paid service, commit, push, PR or remote workflow run had been created at that point.

### GitHub publication and hosted verification

On 2026-10-17, after read-only checks confirmed no local remote or T3 repository identity, the user explicitly requested a **public** repository and granted push/pull permission. M may commit/publish the reviewed foundation to the authenticated account's `procureflow` repository, without force pushes or paid services. Public standard-Linux runner entitlement was checked against GitHub's official billing policy. Artifact uploads and dependency caches are disabled for this initial publication to avoid assumptions about account-wide storage allowances; hosted logs/run URLs carry CI evidence. Hosted checks have now passed; P1 has not started.

- Created [kemselcuk/procureflow](https://github.com/kemselcuk/procureflow) with `gh repo create --public --source=. --remote=origin`, without pushing in that command. `gh repo view` confirms `PUBLIC` and authenticated `ADMIN` access; the repository Actions endpoint confirms workflows are enabled. Local `origin` uses the exact HTTPS repository URL for fetch/push. T3's project metadata still returned `repositoryIdentity: null` after rereading and re-saving the unchanged workspace path; no unsupported database edits or project re-creation were attempted. Git connectivity is independently verified.
- Reviewed the full staged inventory: only the expected foundation, four central documents and text-normalization metadata, excluding dependencies, builds, generated reports, local environment files and downloaded wrapper JAR. Publisher-checksummed Gitleaks 8.30.1 staged and existing-history scans reported no leaks, with output redacted and JSON reports kept under ignored `target/`. The staged scan reported approximately 382 KB; the history scan covered 22 reachable internal/local commits, not just the main branch. This is a scanner result, not proof that no secret could exist.
- `git diff --cached --check` passed after `.gitattributes` plus index-only Windows-wrapper normalization; `actionlint` passed on the publication workflow with no artifact uploads/cache. These publication-only deltas do not change Java/Angular business code. A final staged Gitleaks scan covered approximately 384 KB and reported no leaks.
- Committed the foundation as `57d43622873d05625a13f5d20cce1ad7efbe660f` (`feat: establish verified local application foundation`) and normally pushed `main` with upstream `origin/main`. `git fetch origin main` succeeded; the GitHub commit API matched local HEAD; the tree was clean. The initial publication contained only the initial commit and foundation commit, not T3's internal snapshot refs. [First hosted run](https://github.com/kemselcuk/procureflow/actions/runs/39049180018) completed successfully for that exact revision.
- Independent publication-delta review R2: T3 task `node:delegated-task:command%3Amcp%3Ae639658d-6369-43d8-bec0-7c5ca200a490%3Adelegate-task%3Aprocureflow-p0-publication-sol-review-20261017-r2`; [review report](t3-thread://v1/thread:delegated-task:command%3Amcp%3Ae639658d-6369-43d8-bec0-7c5ca200a490%3Adelegate-task%3Aprocureflow-p0-publication-sol-review-20261017-r2). Provider `opencode`, model `openai/gpt-6.1-sol`, requested/saved variant `high` (not provider-side attestation). Reviewer verified exact commit `57d4362`, R1 source equivalence except CI/Windows line endings, read-only permissions and no artifact uploads/cache. No material findings. One low-severity stale authorization sentence in D010 was confirmed by M and corrected as a documentation-only follow-up; no unresolved review objection remains. Reviewer ran no builds/tests or hosted-run monitoring; M retains hosted-output verification and final phase acceptance.
- Hosted acceptance: `gh run watch 39049180018 --exit-status` exited 0. M fetched `gh run view --json headSha,status,conclusion,url,jobs` and full `--log`, verified the exact revision and three successful jobs, then inspected meaningful command output: Maven 3.9.11/Temurin 21.0.12.1; 3 HTTP tests and 2 real PostgreSQL 18.6 integration tests with zero failures/errors/skips; frontend type checks, 3 passing Vitest tests, audit and production build; three container builds, four healthy services, all four HTTP/proxy checks and isolated cleanup. Metadata/log copies are local ignored files `target/github-ci-39049180018.{json,log}`; the run URL is the hosted evidence. No business-flow, OIDC, budget-concurrency, RabbitMQ recovery or Windows execution result is inferred.
- Final M acceptance: P0.1–P0.4 and the P0 exit gate are satisfied for the reviewed/tested executable revision `57d4362`. This follow-up changes only README/PLAN/DECISIONS evidence and the low-severity wording correction; no application/configuration code changed, so broad suites are not rerun. The documentation-only commit uses `[skip ci]` and cites the actually tested revision rather than claiming that a new application revision was tested. The next development phase is P1; deployment and additional-spend gates remain closed.

Keep future progress entries concise and link to real reports/diffs rather than duplicating phase descriptions.

### P1 execution record

Main-agent work based on `023d736`, initially uncommitted. Implemented JWT signature/issuer/audience/expiry/subject/type validation; live database membership/role resolution; explicit endpoint matrix, company-scoped directory/admin/budget services, shared authorization lock/exclusive membership edits, constrained V2 schema, separate database credentials and append-only application audit permissions. `local-demo` fixtures create two companies/eight distinct actors only under the explicit demo profile and do not restore revoked assignments on restart. No purchase transition, approval or notification behavior is claimed.

Initial observed checks: Java 21 `./mvnw --batch-mode verify dependency:tree -DoutputType=json -DoutputFile=target/dependency-tree.json` passed 3 HTTP slice tests, 2 migration/OpenAPI integration tests and 26 identity/database/token cases (31 total), with zero failures/errors/skips, including concurrent administrator revocation and lock-timeout/audit-actor checks. Token negatives use real ephemeral RSA/JWKS decoding, not a mocked authentication principal. A deliberately denied audit insert returns 500 and rolls back the department write; its expected server stack trace is not a failing test. `npm install`, type checks, 14 Vitest component/service/interceptor tests and production build passed; `npm audit --audit-level=high` reported no vulnerabilities. OSV `/v1/querybatch` checked 162 distinct resolved Maven coordinates/versions with no reported findings (`target/p1-osv-report.json`); this is a point-in-time package query, not exhaustive security or image scanning. Reports remain normal generated test/build output. At that point, independent review and hosted verification remained open; both were subsequently completed below.

Source collection: T3 task `node:delegated-task:command%3Amcp%3Ae639658d-6369-43d8-bec0-7c5ca200a490%3Adelegate-task%3Aprocureflow-p1-keycloak-glm-20261017-r1`, provider `opencode`, `opencode-go/glm-5.3-flash`, requested variant `high`, read-only. M independently fetched the official 26.8.1 release, npm adapter metadata and Quay manifest metadata. Keycloak is pinned to `26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244`, adapter to 26.2.4. The manifest digest is an immutable content pin, not a verified cryptographic publisher signature. Research did not execute login or establish runtime compatibility; separate real-provider evidence follows.

Real-provider integration: `python3 scripts/check-foundation.py` passed in disposable project `procureflow-check-e75f15864b04` on 2026-10-18 local time. Four images built from pinned bases; all five services healthy; readiness, proxied system/OpenAPI, static routing, Java/nginx checks and real Keycloak Code+S256 journey passed. The journey rejects a wrong verifier and code replay, resolves two companies from real JWTs, denies employee administration, refreshes a usable token, applies membership revocation to an unexpired token, logs out and rejects subsequent refresh. Report: `target/foundation-smoke.json`; normal process output is the command evidence. Test-owned containers/volume/network were removed. This is a protocol/HTTP-form integration journey, not a rendered Angular browser test or future purchase/notification acceptance.

Resolved integration failures: desktop file sharing reported an executable initialization hook despite mode 0644 and denied its interpreter. A PostgreSQL-derived image now copies the same hook with explicit 0644, retaining the exact upstream digest. Python CookieJar did not send Secure cookies over trustworthy localhost HTTP as browsers do; the test-only cookie policy permits that solely for localhost/127.0.0.1. Code-replay testing invalidates that Keycloak session, so its disposable negative session is separate from the refresh/logout session. None of these fixes enables password grants, relaxes production cookie policy or changes application authorization.

Independent Sol review R1: task `node:delegated-task:command%3Amcp%3A186955a6-0667-4c6e-a966-30a625a28ade%3Adelegate-task%3Aprocureflow-p1-sol-security-20261018-r1`; [report](t3-thread://v1/thread:delegated-task:command%3Amcp%3A186955a6-0667-4c6e-a966-30a625a28ade%3Adelegate-task%3Aprocureflow-p1-sol-security-20261018-r1), provider `opencode`, model `openai/gpt-6.1-sol`, requested/saved variant `high` (configuration, not provider-side execution attestation). Reviewer found no verified material implementation defect, but one **medium evidence gap**: service rejection alone did not prove the database budget CHECK rejects direct invalid writes. M verified the 67-file source/config manifest SHA-256 `25f30478cc3a206e224cb0c410c409ba849d9a966f6542c31e2ae5fc0e4b2db1`, read the relevant schema/tests and accepted that finding. Added direct runtime-role SQL attempts for below-usage limits and negative usage, asserting SQLSTATE `23514` and unchanged persisted values. Only `IdentityAccessIT.java` changed in the executable snapshot; application/infra/frontend source is unchanged. Closure requires the targeted rerun and independent R2 review. Reviewer inspected existing backend/Compose reports without executing tests/builds/containers or modifying files.

Corrective-test evidence: Java 21 `./mvnw --batch-mode -pl backend/app -am verify -Dit.test=IdentityAccessIT -Dfailsafe.failIfNoSpecifiedTests=false` passed 3 HTTP tests and all 28 identity/security cases, including both direct SQL failures and unchanged amounts; zero failures/errors/skips. The flag permits parent/library modules with no matching tests; the app's 28 cases actually executed. The initial invocation without that flag stopped at the parent before testing. Full migration/OpenAPI tests and the provider/frontend suites were not rerun for this test-only addition. The final 67-file manifest is `8d657d6a9abab0da565d199fdb8914e3e75365b261fbcda6b7b4474276de8028`, generated in ignored `target/p1-r2-manifest.txt`; M's comparison against R1 confirmed only the expected test file changed. The R2 closure is recorded below.

Independent Sol closure R2: task `node:delegated-task:command%3Amcp%3A186955a6-0667-4c6e-a966-30a625a28ade%3Adelegate-task%3Aprocureflow-p1-sol-security-20261018-r2`; [report](t3-thread://v1/thread:delegated-task:command%3Amcp%3A186955a6-0667-4c6e-a966-30a625a28ade%3Adelegate-task%3Aprocureflow-p1-sol-security-20261018-r2), provider `opencode`, model `openai/gpt-6.1-sol`, requested/saved variant `high` (not provider-side attestation). Reviewer verified the final 67-file manifest, sole test-file delta, staging equivalence and real execution of both added SQL cases in the XML report. R1's medium evidence gap is closed; no new finding or unresolved objection. No tests/builds/containers or writes were performed by the reviewer.

M's local acceptance: read both reports and corrective test diff, checked real outputs and final hashes, and accepted the P1 local slice. P1.1–P1.4 and the scoped AC-SEC/AC-DB gate pass; this does not accept future purchase, final-approval, notification or rendered-browser journeys. Main-owned documentation follow-ups only record evidence/status. The public repository/normal push authorization remains the user's explicit 2026-10-17 grant. The staged inventory contained 45 expected source/config/test/document files, no local environments, generated reports, dependencies or credentials; redacted Gitleaks staged scan found no leaks. Publication and hosted P1 verification were still pending at local acceptance; their completion follows.

Publication and final acceptance: committed the reviewed P1 slice as `92688d5a5e619585d246ac2fac1565739f878493`, normally pushed `main`, then fetched and verified both `origin/main` and the GitHub commit API match. M verified all 67 committed source/config blobs against R2's manifest. Final staged and one-commit publication-range Gitleaks scans reported no leaks; no credentials, local fixture passwords or generated reports were published. [Hosted run 39074281305](https://github.com/kemselcuk/procureflow/actions/runs/39074281305) passed backend, frontend and containers on that exact revision. M checked the completed-run metadata and actual full logs: Maven 3.9.11/Java 21.0.12.1; **3 HTTP + 2 migration/OpenAPI + 28 identity/database cases (33 total)**, zero failures/errors/skips; **14 Angular tests**, type checks, production build and npm audit; four image builds, five healthy services, real Code+S256/replay/refresh/logout/revocation journey and isolated cleanup. Metadata/log copies: ignored `target/github-ci-39074281305.{json,log}`. Container packaging deliberately skips its duplicate test execution; the separate backend job runs the complete suite.

M accepts **P1** for that reviewed/tested executable revision, with no unresolved review objection. This final documentation-only follow-up records the hosted evidence and phase status, uses `[skip ci]`, and changes no application/configuration/test source; it does not claim a new executable revision was tested. P2 is next. Purchase/history/notification isolation, approval concurrency and rendered browser journeys remain for their owning phases. No PR, cloud resource, deployment, paid runner/storage or additional model/API spend was introduced.
