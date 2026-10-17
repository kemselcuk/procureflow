# Working agreement

## Current authorization

The user explicitly requested implementation on 2026-10-17 ("uygulamaya başla") after receiving the independently reviewed detailed plan. That approval was recovered from the previous thread before execution began; see the [approval record](docs/PLAN.md#progress-and-review-record).

Local implementation of P0–P7 in the approved plan is authorized, including application code, scaffolding, dependencies, builds, tests and local services. Start with the P0 compatibility/foundation gate and respect subsequent phase exit criteria. Do not silently start or install a container runtime if engine access is unavailable.

On 2026-10-17 the user separately authorized creating a **public** GitHub repository and push/pull operations. This includes committing and publishing the reviewed foundation and subsequent approved project work; inspect staged content and protect credentials before every publication. Use normal non-force Git operations. Creating PRs or publishing unrelated messages is not implied.

Paid services and cloud provisioning/deployment remain unauthorized. Standard public-repository Linux CI may run within the zero-extra-spend boundary; do not activate paid runners or billable storage. A committed baseline must exist before isolated writing delegation. The approved product scope and cost boundaries remain in the plan.

## Document ownership

- `README.md` owns installation/use instructions and the technology-to-real-evidence index. Mark incomplete features as planned.
- `docs/PLAN.md` owns product scope, tasks, dependencies, acceptance criteria, progress and cost boundaries.
- `docs/DECISIONS.md` owns material decisions, rationale, source research and unresolved alternatives.
- This file owns the agent workflow. Link to other documents instead of copying their contents.

Keep documentation and code comments in English. Talk to the user in Turkish. Create no phase-specific or per-agent Markdown files by default. Put a material decision in the decision log and a task/review outcome in the plan.

## Responsibility and delegation

The user makes final product decisions and grants the agreed approvals. The main agent retains technical acceptance, coordination and integration responsibility.

Use at most two child-agent slots and at most two simultaneous delegated tasks, excluding the main agent. Subagents must not create further agents. Group independent, bounded work; the main agent handles trivial edits and tightly dependent steps when delegation adds no value.

Use `gpt-6.1-sol` for architecture, main development, test design and independent review. When delegation helps, use the existing OpenCode Go subscription for bounded routine work and simple coding tasks with agreed contracts and acceptance criteria, including fixtures, routine components/tests, source collection and documentation checks. Choose either `opencode-go/deepseek-v4.1-flash` (reasoning variant `max`) or `opencode-go/glm-5.3-flash` (a live-catalog reasoning variant appropriate to the task) according to task fit and current availability; do not start both by default. Keep security, tenant isolation, budget/concurrency correctness and shared contracts with Sol. Do not switch to a paid API or an unapproved provider to bypass a quota failure. Report the limitation and continue within the approved tools where possible.

Before delegation, consult the live model catalog. Use T3 `delegate_task` for every delegated task, including same-provider Sol work, and retain the returned task ID. Do not use Codex-native subagent or collaboration tools for this project while the subscription-sharing incompatibility remains unresolved; see the rationale and evidence in [D010](docs/DECISIONS.md#d010--four-central-documents-and-bounded-agent-roles). If compatible T3 delegation is unavailable, keep the work with the main agent or report the limitation. Verify the requested model/options from T3 configuration when available; distinguish a saved configuration from a provider-side execution attestation. This routing policy does not authorize implementation before the development gate opens.

Each delegated brief must state the question, deliverable, allowed paths, forbidden paths, dependencies, acceptance evidence and whether writes are permitted. Research and review agents own no files. Their report alone is not acceptance evidence.

## File and worktree discipline

After implementation is authorized, each writing subagent gets a separate worktree and a bounded path list. Read-only research/review may share the main checkout. Do not create worktrees or run their setup during planning.

T3 worktree tasks start from the parent commit; uncommitted parent changes are not copied. Establish a user-authorized committed baseline before delegating isolated implementation, or keep the writing task with the main agent until that baseline is available. Do not silently commit to satisfy this prerequisite.

One active writer owns a file at a time. The main agent owns shared build files, event/API contracts, migrations, Compose configuration, CI configuration and the four central documents unless a task explicitly transfers one of those paths. Agree contracts before parallel work. Separate test ownership from source ownership where independent test work is useful.

Reviewers must not be authors of the change they review. Give a reviewer the exact revision/diff, accepted requirements, relevant decisions, test evidence and unresolved concerns. Each new T3 review round uses a new delegated task and request ID, including prior findings and responses.

## Technical acceptance

The main agent reads the changes, validates important sources and checks actual command output. It resolves review findings, reviews any corrective diff and verifies integration. Do not accept a child agent's “done” message as completion.

Use real PostgreSQL and RabbitMQ where their behavior is under test. Keep budget and tenant invariants as explicit acceptance cases; mocks cannot prove database concurrency or message recovery. Match tests to the changed behavior; do not add tests that merely mirror a trivial implementation.

Record the tested revision, command, outcome, relevant report/run link and limitations in the plan; link that evidence from the README when it supports a technology claim. Distinguish planned checks, observed passing checks and checks not run. Do not invent metrics or call a document-checked stack runtime-tested.

For unresolved findings, record severity, response and disposition. Retest after meaningful fixes; do not rerun broad suites without a reason. The main agent alone accepts an integration result, subject to the user's agreed checkpoints.

## Engineering safeguards

Use the product rules and acceptance catalog in the plan and the architecture in the decision log. In particular, company scope must come from validated identity/membership, every approval must preserve the budget invariant, and state/audit/outbox records must commit atomically.

Keep framework-managed dependencies aligned with the researched baseline. Any override needs a documented compatibility or security reason and verification. Pin resolved dependencies and container images; never introduce unreviewed `latest` tags into reproducible instructions.

Use no Redis or additional service without a concrete requirement and a recorded decision. Do not add an AI feature to the product merely because AI agents build it.

## Cost and external actions

The current allowance for additional services and model/API charges is EUR 0. Existing subscriptions are the model-access route. The total infrastructure reserve across all five portfolio projects is EUR 10–20; it is not a monthly or per-project allowance.

Cloud deployment is deferred until the user requests it. A Terraform configuration, validation, plan and apply are distinct actions. Do not provision resources, buy a domain, activate paid CI/model features or deploy while preparing documentation. The cloud cost/approval boundary is owned by the plan.

Keep credentials out of files, logs, review prompts and committed demo data. Treat local fixtures as local-only. Follow the session's authorization for GitHub operations; implementation approval does not by itself authorize publishing messages or spending money.

## Learning loop

Explain consequential decisions in Turkish: what changed, why it was chosen, the main alternative and the evidence. The user has less Angular experience, so give frontend changes extra explanation and occasional small exercises. Use short interview questions at meaningful checkpoints, without stopping for approval on every routine implementation detail.
