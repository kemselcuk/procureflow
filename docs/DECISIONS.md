# Decision record

Date: 2026-10-17. Status: decisions proposed by the detailed plan unless explicitly marked as a confirmed user choice. The [plan](PLAN.md) owns product rules, task status and acceptance criteria. This file owns rationale and primary-source research.

## D001 — A modular monolith and one notification worker

Confirmed approach: Java 21/Spring backend with a complete Angular UI. Use a modular monolith for synchronous business operations and a separate worker for asynchronous notifications. Explicit package/module boundaries keep the important purchase, budget and audit transaction in one PostgreSQL database transaction. A small event-contract module can be shared; the worker does not import request/budget repositories.

Alternative: splitting purchasing, approvals and budgets into services would add distributed consistency work without helping this portfolio's initial goal. No Redis is needed for correctness: database constraints/locks and durable broker/database state cover the concrete requirements. Revisit additional infrastructure only for a measured requirement.

## D002 — Version baseline and compatibility gates

Research date: 2026-10-17. **These are documentation-checked candidate choices, not a runtime-tested dependency lock.** Exact patch/image pins are accepted only after P0 resolves metadata, dependencies, security and startup tests. A documentation sidebar listing an old version is not evidence that the line still receives public maintenance.

| Component | Planning baseline | Verified constraint / remaining gate |
| --- | --- | --- |
| Java | Java 21 LTS | Installed Temurin 21.0.8 exists; choose a maintained Java 21 security patch when implementing. Maven currently uses another JDK and must be aligned |
| Spring Boot | 4.1.1 candidate | Official requirements include Java 17–26. The wiki names 4.1 and 4.0 as actively maintained. No preview/snapshot baseline |
| Spring libraries/JPA/JUnit/Mockito | Versions managed by the selected Boot BOM | Avoid independent framework version selection. Confirm actual effective POM and test runtime under Java 21 |
| Maven | 3.9.11 wrapper candidate | Installed version exceeds Boot's stated Maven 3.6.3 minimum. Wrapper/toolchain behavior still needs execution evidence |
| OpenAPI | Springdoc 3.1.1 candidate, Boot 4-compatible 3.x line | Upstream #3373 distinguishes Gradle's Jackson upgrade from Maven/Boot-parent management, which retains Boot's version below Swagger's declared dependency. Check the primary security advisory and actual Maven tree; accept only a justified, tested version set and OpenAPI serialization smoke test at P0 |
| Angular | 22.2.3 planning candidate | Publisher npm metadata lists 22.2.3 as `latest`; exact core/compiler/CLI metadata was read successfully. Recheck stable support before P0 pins the lockfile |
| Node / TypeScript / RxJS | Maintained Node 22 patch; TypeScript 6.0.x and a maintained RxJS 7.x patch within peers | Angular 22.2.3 core/compiler-cli/CLI declare Node `^22.22.3 || ^24.15.0 || >=26.0.0`; installed 22.23.2 fits the first range. Compiler-cli requires TypeScript `>=6.0 <6.1`; core accepts RxJS `^6.5.3 || ^7.4.0`, including later 7.x patches. Metadata fit is not build evidence |
| Frontend OIDC | Framework-independent `keycloak-js` 26.2.4 candidate | The adapter is released separately from the server. Avoid an unverified Angular wrapper dependency; verify actual login/refresh/logout against the chosen server |
| PostgreSQL / Flyway | PostgreSQL 18.x preferred; 17.x is a supported alternative if P0 reveals a concrete constraint | Both PostgreSQL lines are supported, and Keycloak lists both (tested: 18). Flyway's database page verifies 18; Boot 4.1.1 manages Flyway 12.4.0 and its PostgreSQL module. Prefer one major for the app and Keycloak, with separate databases/credentials; verify migrations against the actual pinned image |
| Testcontainers | Boot-managed 2.0.5 candidate | Boot table includes `testcontainers-junit-jupiter`, `testcontainers-postgresql` and `testcontainers-rabbitmq`; do not copy the old 1.x artifact names blindly |
| Keycloak | 26.8.1 planning candidate | Official downloads and database matrix checked. Separate container/runtime from application Java; verify host/container issuer/redirect URLs. `start-dev` is local-only |
| RabbitMQ | Community-supported 4.3.x, observed patch 4.3.6 | Official table lists community support through 2027-01-31; recheck before implementation/deployment. Boot-managed AMQP client and broker behavior still require real integration tests |
| Mailpit | 1.31.6 planning candidate | Official project release published 2026-10-15. Local capture only; verify SMTP/API settings and bind its UI to local access. No LTS or real delivery claim |
| Playwright | Maintained stable release pinned at implementation | Current documentation supports maintained Node 22/24/26. Record package/browser binary alignment and runner prerequisites |
| Terraform | Deferred until target provider/version is selected | No provider is selected and Terraform is not installed. Configuration validation and actual deployment are distinct deliverables |

Primary sources read during planning:

- [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html): Java and build-tool constraints.
- [Spring Boot maintained lines](https://github.com/spring-projects/spring-boot/wiki): maintained major/minor lines; exact lifecycle dates must be rechecked at implementation.
- [Boot dependency management](https://docs.spring.io/spring-boot/reference/using/build-systems.html) and [managed coordinates](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html): managed dependencies, Flyway and Testcontainers versions. Overrides are possible but require a reason and verification.
- [Springdoc compatibility](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot), [3.1.1 release](https://github.com/springdoc/springdoc-openapi/releases/tag/v3.1.1) and [upstream dependency report #3373](https://github.com/springdoc/springdoc-openapi/issues/3373): 3.x targets Boot 4; the report describes a Jackson conflict on Boot 4.1.1. A report is a risk signal, not a reproduced result in this repository. Do not downgrade blindly to avoid security fixes.
- [Angular compatibility](https://angular.dev/reference/versions) and [support schedule](https://angular.dev/reference/releases): coordinated Angular/Node/TypeScript ranges and the active 22 line. Earlier raw GitHub package reads failed; successful publisher npm metadata reads now cover [dist-tags](https://registry.npmjs.org/-/package/@angular/core/dist-tags), [core 22.2.3](https://registry.npmjs.org/@angular/core/22.2.3), [compiler-cli 22.2.3](https://registry.npmjs.org/@angular/compiler-cli/22.2.3) and [CLI 22.2.3](https://registry.npmjs.org/@angular/cli/22.2.3). Installation/build remains a P0 check.
- [Boot database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html): PostgreSQL needs the Flyway database-specific module; use one schema initialization mechanism.
- [Keycloak JavaScript adapter](https://www.keycloak.org/securing-apps/javascript-adapter): code flow and in-memory access/refresh tokens.
- [Playwright requirements](https://playwright.dev/docs/intro#system-requirements): supported Node and host platforms.
- [PostgreSQL version policy](https://www.postgresql.org/support/versioning/), [Flyway PostgreSQL support](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database), [Keycloak database matrix](https://www.keycloak.org/server/db) and [downloads](https://www.keycloak.org/downloads): supported database majors and separately released server/adapter versions.
- [RabbitMQ release information](https://www.rabbitmq.com/release-information), [Keycloak containers](https://www.keycloak.org/server/containers), [Mailpit 1.31.6](https://github.com/axllent/mailpit/releases/tag/v1.31.6) and [SMTP configuration](https://mailpit.axllent.org/docs/configuration/smtp/): observed release/support boundaries and local service settings. Container tags/digests have not been resolved or pulled.

Do not claim “all versions verified together” from this source review. P0 must inspect actual resolved artifacts and handle the Jackson risk with an appropriate maintained Boot/Springdoc/security-patched combination, documenting any justified override and its tests.

### P0 dependency choices (implementation, 2026-10-17)

The root POM selects stable Boot 4.1.1 and Springdoc 3.1.1 (API only; a Swagger UI dependency is unnecessary for the current foundation). Maven Central metadata still lists 4.2 as a milestone, not a stable alternative. Framework-managed dependencies remain on the Boot BOM except for these explicit same-line security overrides:

- `jackson-2-bom.version=2.21.7` and `jackson-bom.version=3.1.7`. The [reviewed primary advisory GHSA-q4xh-88c3-wmh7 / CVE-2026-68497](https://github.com/advisories/GHSA-q4xh-88c3-wmh7) affects both default Boot families (2.21.5 and 3.1.5); the fixed minima in these lines are 2.21.6 and 3.1.6. Main-agent reads of the actual Boot POM, Maven Central metadata and advisory independently confirm the source-collection result. A major/minor Jackson upgrade is avoided. Swagger 2.2.55 declares Jackson 2.22.1, so running it on Boot's patched 2.21 line remains a compatibility check, not a source-only conclusion.
- `tomcat.version=11.0.27`. An OSV query of the resolved Maven graph flagged 11.0.24 for GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6 and GHSA-h3x4-894j-xpx5. The [Apache security page](https://tomcat.apache.org/security-11.html) confirms fixes in 11.0.25 and further fixes in 11.0.26, including request-header mix-up CVE-2026-86350. [Official artifact metadata](https://repo.maven.apache.org/maven2/org/apache/tomcat/embed/tomcat-embed-core/maven-metadata.xml) lists 11.0.27 as the newest stable 11.0 patch. Keep the Tomcat family aligned through Boot's property rather than overriding one JAR. Our configuration does not use DIGEST/FORM realms or AJP, but retaining a known affected baseline provides no benefit.

The actual resolved graph, HTTP/OpenAPI tests, repeat vulnerability query and limitations are owned by the plan's execution record. A clean OSV/npm result only describes those databases at query time; it is not a comprehensive security certification or an OS-image vulnerability scan. Reconsider overrides when a stable Boot patch manages the fixes.

Frontend direct dependencies are exact: Angular/CLI/build/compiler 22.2.3, TypeScript 6.0.3 (not the incompatible npm `latest` 7.x), RxJS 7.8.2, tslib 2.8.1, Vitest 5.0.5 and jsdom 30.1.3. The npm lockfile fixes resolved transitives. `.nvmrc`, container build and CI select Node 22.23.3; local initial checks used compatible installed Node 22.23.2. Analytics is disabled. The UI is standalone and zoneless, with a component-local discriminated signal state and a small HTTP service; no global state library, router or authentication wrapper is introduced before it is needed.

Container references use exact tags plus immutable multi-architecture digests from Docker Hub metadata: PostgreSQL 18.6-bookworm, Temurin 21.0.12.1_1 JDK/JRE on Jammy, Node 22.23.3-bookworm-slim and nginx 1.30.6-alpine3.24. Testcontainers 2.0.5 requires a **digest-only** PostgreSQL reference to parse the repository identity correctly; it resolves the same image digest as Compose, rather than using a different tag. Digests live beside the executable configuration, not in a second hand-maintained lock table. Keycloak, RabbitMQ and Mailpit remain for their owning phases.

Java 21.0.12.1+1 was downloaded from the [official Temurin release](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1) into the session's approved temporary toolchain directory and verified against the release asset SHA-256. This does not change the user's system Java/Maven installation. The wrapper selects Maven 3.9.11 with a distribution SHA-256 independently computed after checking the publisher SHA-512. Enforcer requires the running Maven JVM to be Java 21, while the compiler release is also 21. CI and container stages select the current Java 21 patch explicitly.

P0 creates only the tenant-root `company` schema, without demo companies, memberships or operational purchase data. Flyway owns it and JPA validates the mapping. The app exposes public read-only foundation metadata, OpenAPI and non-detailed health; all other routes deny by default until P1 implements JWT/membership authorization. Boot's generated-password user service is disabled because no password login is offered. The worker is an optional process/health scaffold, **not a functioning notification consumer**. Compose's database-owner role is local-only; restricted runtime/migration roles are part of P1, before adding business data. App/frontend runtime containers are unprivileged, read-only except for temporary storage, and host ports bind only to loopback.

Fresh-container testing exposed an official `only-script` Maven-wrapper behavior: it switches from ZIP to TAR if `unzip` is absent, while the distribution has a ZIP-specific checksum. Use the official 3.3.4 `script` wrapper instead: it downloads a checksum-pinned bootstrap JAR and uses Java ZIP extraction against the checksum-pinned Maven distribution. The generated JAR is ignored by Git and Docker context, so clean container builds exercise the download and verification. No system package or modified upstream wrapper script is needed. The bootstrap JAR was fetched over Maven Central TLS, checked against its published SHA-1 and pinned locally by SHA-256; the Maven distribution was checked against publisher SHA-512 before calculating its SHA-256.

The disposable Compose checker uses a unique project name, Docker-assigned random loopback host ports and an in-memory generated local password. This avoids stopping the user's unrelated projects or depending on fixed host ports being free. Cleanup deletes only the checker's own labelled project/volume. Default interactive ports remain configurable, and no password is written by the checker to the checkout or reports.

Publication adds `.gitattributes` to normalize text to LF in Git while checking out the generated Windows wrapper as CRLF. The first staged whitespace check exposed its upstream CRLF bytes; index-only renormalization preserves the script's content and Windows checkout format. No generated wrapper logic was edited. Windows execution remains untested.

## D003 — Explicit company scope and scoped membership

Use a shared application schema with a non-null company key on tenant-owned records. Resolve the user by validated JWT issuer + subject, then an active membership; never take company authority from a request body, header, selected URL or opaque ID alone. The first release has one active company membership per identity.

Use Keycloak for authentication and Spring Security as a standard JWT resource server. The application membership/role assignments are the source of company-specific authorization; avoid two independently editable copies of the same business-role policy. Membership changes require company-administrator authority, and coarse authenticated routes never replace resource/action checks.

Re-read active membership and current application roles for commands rather than trusting business roles embedded in a still-valid token. Derive the submission department from the validated membership; client-provided company/department values cannot expand its scope. The plan owns the proposed role/state matrix, including visibility limits and changes after submission. This keeps revocation and department assignment in the same application policy instead of depending on token refresh timing.

Scope repository reads and mutations by company. Enforce composite company/entity foreign keys and unique constraints where relationships cross tables, including department/request/membership/notification relationships. An unscoped repository lookup must not become an authorization shortcut. Company administrators are not global administrators.

Row-level security and a database per company are alternatives. The initial design uses explicit scoping plus relational constraints and a comprehensive negative-test matrix to keep the policy visible in Java. It does not claim that one shared application database role prevents a malicious administrator or arbitrary SQL from reading all companies.

References: [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [PostgreSQL constraints](https://www.postgresql.org/docs/current/ddl-constraints.html). Validate issuer, signature, expiry and intended API audience; use a public SPA client with Code + PKCE, in-memory tokens and restricted redirects/origins. Local development OIDC configuration must not be presented as production configuration.

### P1 implementation contract

Separate authentication bootstrap from company authorization. A valid JWT may idempotently register only its own `(issuer, subject)` through `POST /api/identities/self`; this exposes no tenant data and never creates a company/membership/role. Company administrators may link that existing identity by opaque ID, without querying a global identity directory. All company endpoints re-read the current active database membership and explicit roles. Foreign resource IDs remain 404 and do not establish authority. The plan owns the endpoint matrix.

For commands, acquire a company-row shared lock before re-reading authorization; membership/role changes instead take an exclusive lock on that same company row. This serializes revocation against already-authorized commands without locking an arbitrary pair of administrators in conflicting order. Within a company, membership mutations are rare enough for this explicit coarse authorization lock. Subsequent purchase commands still acquire request then budget locks in D004 order, after the authorization guard; no command may invert these orders. Read endpoints recheck membership each call but do not hold a lock for an entire browser session. No cached token business roles are used.

Identity/membership scoping uses explicit SQL queries so composite tenant predicates and authorization locks are visible; monthly budgets use JPA mappings/repositories with explicit company predicates. Both participate in the same Spring database transaction. The database enforces one active company membership per identity, tenant-composite department/membership/budget relationships, explicit role values and unique scoped assignments. Application-level policy also prevents removing the last active company administrator. Database migration and application credentials are separated before business data is introduced; the application cannot alter schema or rewrite audit rows.

Keycloak 26.8.1 and keycloak-js 26.2.4 remain the selected stable versions after main-agent validation of the [official server release](https://github.com/keycloak/keycloak/releases/tag/26.8.1), [npm metadata](https://registry.npmjs.org/keycloak-js/26.2.4) and [Quay tag/manifest metadata](https://quay.io/api/v1/repository/keycloak/keycloak/tag/?filter_tag_name=eq:26.8.1). Server release notes enumerate security fixes; repository advisories alone are not an exhaustive advisory feed. The image has a multi-architecture immutable digest, not a claimed publisher-signature verification.

The local realm has a public client with Code flow only, required S256, exact frontend root redirects/origins, no direct/password grants, and an access-token-only custom API audience. Passwords are environment placeholders, never committed values. An external loopback issuer and separate container-reachable JWKS URL allow signature validation without trusting forwarded/client headers. Tokens stay in adapter memory, and the interceptor attaches them only to same-origin private `/api/` requests after refresh. Database membership is authoritative even when token claims advertise administrator roles. The local HTTP realm (`start-dev`, loopback-bound ports) is explicitly not production OIDC configuration.

The repeatable provider probe uses actual authorization forms, cookies, code exchange, PKCE, refresh and logout over HTTP plus real protected API calls; it is not password grant and not a mock provider. This proves server/protocol integration, not rendering or the adapter's behavior in a real browser. Angular adapter behavior has separate service/interceptor tests; rendered browser journeys remain for the frontend/system phases. Scoped application data and access boundaries must still pass independent security review before P1 acceptance.

## D004 — Final approval owns the budget transaction

The plan's fictional budget is accounting data, separate from the user's real infrastructure reserve. Pending requests do not reserve funds. Final approval uses a single command timestamp to identify the UTC approval month and locks the request row before its company/department/month budget row. Recheck state, current membership/role, requester separation and previous approver before charging.

Use database row locks under a documented isolation level (initially PostgreSQL READ COMMITTED) rather than a JVM mutex. Different app instances must obey the same invariant. Requests use decimal amounts and fixed scale; budget checks live in the transaction and database constraints. A unique request charge plus unique approval-step records backstop duplicate execution. State, charge, usage, audit and outbox are atomic.

All commands needing both rows use the same lock order. Budget limit changes lock the budget and cannot lower the limit below usage. Explicit conflicts are preferable to a successful response with inconsistent effects. Freeze amount/department after submission; terminal approved requests have no cancellation/refund path in this release.

Optimistic locking and conditional SQL updates are alternatives. Pessimistic locking makes the shared budget contention and correctness argument explicit for this scope. A version field can detect stale UI commands, but it does not replace the shared-budget lock. Tests must use separate transactions/connections and barriers, including rollback and approval/cancel races.

Reference: [PostgreSQL explicit locking](https://www.postgresql.org/docs/current/explicit-locking.html) documents conflicting row locks and consistent acquisition order. Use short database transactions without broker/SMTP calls while holding purchase/budget locks. Configure bounded lock/transaction waits and return a retryable conflict where appropriate; no silent retry may duplicate business effects.

## D005 — Transactional outbox with at-least-once delivery

Business transactions insert an event into PostgreSQL; they never require a successful RabbitMQ connection to commit a purchase operation. The relay claims pending rows with a recoverable lease, publishes outside the purchase transaction, uses persistent messages/durable topology and checks mandatory routing plus publisher confirms. Only then does it mark a row published.

A relay crash after confirmation but before its database update can publish the same event again. Preserve stable event IDs. The worker uses an inbox/unique delivery key and commits in-app notifications and email-job records atomically before acknowledging the broker message. It validates recipient/company relationships and mutates no purchase/budget tables. Use separate database credentials/grants where practical to enforce that boundary.

Retry transient failures with bounded backoff; malformed/unsupported poison events go to visible quarantine/dead-letter handling. A documented authorized replay uses the same event ID and safe deduplication. Multiple relays/workers must claim safely if later scaled; local deployment uses one of each and one broker, with no high-availability claim.

[RabbitMQ publisher confirms and consumer acknowledgements](https://www.rabbitmq.com/docs/confirms) describe different stages of delivery. A publisher confirmation is not a business-consumer acknowledgement. Durable broker/database volumes and explicit restart tests are required; topology flags alone are not recovery evidence.

## D006 — Durable SMTP jobs and an explicit limitation

The notification worker separately leases database email jobs, sends to local Mailpit and records success/failure. Message IDs and correlation IDs allow observations to be matched to jobs. In-app creation and email-job materialization are idempotent per event/recipient/channel.

SMTP acceptance and saving “sent” to PostgreSQL are not one atomic transaction. A crash in between may cause a duplicate email on retry. A stable Message-ID is useful for correlation but does not guarantee that SMTP recipients deduplicate. Promise eventual completion after a temporary worker outage and unique database effects, not exactly-once external email delivery. Test the ordinary redelivery path and document the ambiguous acceptance window.

Mailpit is local demonstration infrastructure, not a real transactional email provider. Permanent delivery failures are visible and require correction/replay; “eventually completes” assumes recoverable infrastructure and valid recipients.

## D007 — Database migrations, fixtures and audit evidence

Flyway is the schema owner; JPA validates it. Never use Hibernate `update` as a substitute for migrations. Versioned migrations add tenant constraints, indexes, unique charges/steps and outbox/notification tables. Keep local/demo data explicitly enabled and separate from production schema migrations and CI-specific fixtures.

Audit rows are append-only through application behavior and restricted application permissions where practical. Capture business transitions in the same transaction; external application logs are not an authoritative substitute. Do not claim cryptographic tamper-proof history or regulatory certification. Avoid logging credentials/tokens and minimize event payloads.

## D008 — Verification at the boundary being claimed

Use JUnit/Mockito for isolated policies and collaborator behavior, PostgreSQL Testcontainers for transactions/constraints/migrations, real RabbitMQ for broker behavior and Mailpit observations for email. Use Playwright for the complete critical user journey; it complements rather than replaces deterministic concurrency/recovery integration tests.

Tests cover acceptance invariants, not arbitrary coverage percentages. Fresh-checkout instructions and CI must use the reviewed version pins. The README will link real code/test/run evidence; until then every technology claim is labelled planned. The user removed the video requirement; no recording is needed for completion.

## D009 — Portable local runtime; cloud is deferred

Confirmed choice: build and verify locally with Docker Compose first. Keep application state in PostgreSQL/RabbitMQ volumes, configuration external, URLs/issuer/redirects environment-specific and secrets outside committed files. Use minimal health/readiness and graceful shutdown/retry handling. Keycloak production startup, TLS/proxy trust, backups and public ingress need a separate deployment configuration.

Cloud-ready does not mean already deployed or already load-tested. Once a provider is selected, Terraform can describe the specific environment and lifecycle. Creating a configuration, validating it, obtaining a provider plan and applying it are separate evidence/action levels. The [plan](PLAN.md#cost-and-cloud-boundary) owns the total EUR 10–20 five-project reserve and the zero-extra-spend current boundary.

On 2026-10-17 the user chose public GitHub publication and authorized push/pull. The initial CI uses standard `ubuntu-24.04` GitHub-hosted runners, which the [official billing policy](https://docs.github.com/en/billing/concepts/product-billing/github-actions) makes free for public repositories. Artifact storage is shared with the account's allowance and is not automatically an unlimited free resource. Therefore, initial publication disables artifact uploads and optional dependency caches rather than assuming unused account-wide storage. CI command logs/run URLs provide hosted evidence; local test reports remain available. Revisit stored artifacts only after verifying an explicit free storage allowance; do not enable larger runners, paid storage or cloud deployment.

## D010 — Public documentation boundaries

README owns installation/use and links to observed evidence. The plan owns product scope, tasks, acceptance and verification outcomes. This decision log owns architecture and source rationale. Personal agent instructions, model-routing policies and learning preferences stay outside the versioned project. Previously published copies remain in Git history unless a separately approved history rewrite is performed.
