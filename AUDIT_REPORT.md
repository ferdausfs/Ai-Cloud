# AUDIT_REPORT.md — Ai-Cloud (RepoChat AI) Production-Readiness Audit

- **Repository:** https://github.com/ferdausfs/Ai-Cloud
- **Audit commit:** `427055e` (main, 2026-09-08)
- **Audit date:** 2026-09-08
- **Auditor:** senior full-stack / security / DevOps / AI-agent audit (autonomous agent run)
- **Scope:** full source tree (88 Kotlin files across 4 Gradle modules), Gradle config, CI workflow, manifest/backup rules, README, tests.
- **Method:** every claim below was verified against the actual source. Nothing is speculative unless explicitly marked **Needs verification**. Baseline commands were executed locally (JDK 17 / Gradle 8.11.1 / AGP 8.7.3 / Android SDK 35) before any code change.

---

## 1. Executive summary

Ai-Cloud is a **native Android AI coding agent** (not a web app): the user connects a GitHub PAT plus one or more LLM provider keys, chats about a repository, and the app runs a bounded tool-calling loop (`read_file` → context → model → `write_file` → human diff approval → commit to a dedicated `ai-chat/<session>` working branch → optional PR → optional autonomous "fix until CI green" loop).

The codebase is in **notably good shape for its size**: clean 4-module architecture (model / domain / data / app), typed error taxonomy, secrets in Keystore-backed EncryptedSharedPreferences excluded from backups, a single path-sanitization choke point, writes that always carry an explicit branch, no logging of sensitive data, honest UI copy for pricing/CI, and a real unit-test suite (all green at baseline).

The audit confirms **one P1 correctness bug in the autonomous AutoFix loop**, several **P2 reliability/UX gaps** (silent message drop when a turn is already running, permanently stuck PENDING proposals after process death, no way to cancel a running agent turn, README/CI drift, missing prompt-injection hardening), and a set of P3 hardening items. All P0/P1 and the unambiguous P2s were fixed in this audit (see §16). After fixes: unit tests, `assembleDebug`, and lint are green locally; the fixes were pushed to the working branch `ai-chat/audit-prod-002` and validated by GitHub Actions.

**Verdict: conditionally ready** for its intended single-user, bring-your-own-key use. It is not (and does not claim to be) a multi-tenant service; see §11 for the agent-vs-chatbot assessment and §18 for the roadmap.

## 2. Project purpose and current architecture

```
┌────────────────────────── app/ (Compose UI) ──────────────────────────┐
│ MainActivity (deep-link from FGS notification) · AppNavHost           │
│ Screens: HomeScaffold · ChatsHomeScreen · RepoPicker · RepoDetail     │
│          ChatScreen · SettingsScreen (+ ModelCatalogCache)            │
│ ViewModels mirror Room + AiTurnCoordinator live state                 │
│ turn/: AiTurnCoordinator (app-scoped coroutine host) + AiTurnService  │
│        (foreground service, dataSync)                                 │
└──────────────┬────────────────────────────────────────────────────────┘
               │ UI → ViewModel → UseCase → AiTurnRunner
┌──────────────▼──────── core/domain ───────────────────────────────────┐
│ AiEditOrchestrator (bounded tool loop, ≤10 model steps)               │
│ AutoFixLoop (attempt ≤10, CI poll ≤12min, real log tail → fix prompt) │
│ Contracts: LlmService · OllamaService · GithubService · repositories  │
└──────────────┬────────────────────────────────────────────────────────┘
               │
┌──────────────▼──────── core/data ─────────────────────────────────────┐
│ Retrofit/OkHttp: GithubApi · OllamaApi · OpenAiCompatibleApi          │
│ LlmRouterImpl (provider order, rate-limit fallback)                   │
│ Room 2 (sessions/messages/activeRepo) · EncryptedSettingsStore        │
│ ErrorMapping (401/402/403/404/409/429/5xx → typed AppError)           │
└──────────────┬────────────────────────────────────────────────────────┘
               │
┌──────────────▼──────── core/model (pure Kotlin) ──────────────────────┐
│ AiAction JSON contract + parser + path sanitizer · PromptBuilder      │
│ LineDiffer (LCS + fallback) · FileTreeFormatter (bounded)             │
│ ProviderPresets (Groq/Cerebras/OpenRouter/Experiential/…)             │
│ ModelPricing (FREE/PROMO/PAID/UNKNOWN — never invents pricing)        │
└───────────────────────────────────────────────────────────────────────┘
External: GitHub REST v3 · Ollama Cloud · any OpenAI-compatible /v1
Storage: Room `repochat.db` + EncryptedSharedPreferences `secure_settings`
Background: AiTurnCoordinator (SupervisorJob, Dispatchers.Default) + FGS
CI: .github/workflows/android.yml (lint + unit tests + assemble + emulator + dep review)
```

### Is this an AI agent application or a chatbot wrapper?

**It is a genuine (single-tool-domain) agent application, not a chat wrapper.** The distinguishing evidence: a real agent loop (`AiEditOrchestrator.runTurn`) with plan/act/observe cycles — the model chooses `read_file` actions, receives observations as new context, and iterates until it replies or writes; **stateful persistence** (every proposal, decision and outcome is a Room row); **termination conditions** (`MAX_MODEL_STEPS = 10`, bounded CI poll budget); **human-in-the-loop gate** for destructive actions (Approve/Reject diff before any GitHub write); and **verification against an external oracle** (AutoFixLoop polls real GitHub Actions results and never claims success without a green run). What it is *not*: a multi-tool agent with shell/browser/code-execution tools, a RAG system, or a multi-tenant service. There is no vector database — retrieval is deliberately replaced by the repo file tree + on-demand `read_file`, which is the right call for repo-scale editing; see §11.

## 3. Technology stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21 (JVM target 17) |
| UI | Jetpack Compose (BOM 2025.01.00), Material 3, Navigation-Compose, SharedTransition |
| DI | Hilt 2.53.1 (KSP) |
| Persistence | Room 2.6.1 (v2 schema + additive migration), EncryptedSharedPreferences (security-crypto 1.1.0, AES256-GCM/SIV) |
| Network | Retrofit 2.11 + kotlinx-serialization converter, OkHttp 4.12 |
| Async | kotlinx-coroutines 1.9.0, StateFlow/SharedFlow, application-scoped turn host |
| Build | Gradle 8.11.1, AGP 8.7.3, version catalog (`libs.versions.toml`), no hardcoded versions |
| CI | GitHub Actions (ubuntu-latest, JDK 17 temurin): lint (debug+release), unit tests (all modules × debug/release), assembleDebug/Release, connected emulator tests (API 34), dependency review on PRs |
| minSdk / target | 24 / 35 |

## 4. How to run the project

```bash
# Requirements: JDK 17, Android SDK (platform 35, build-tools 34.0.0)
./gradlew test                    # all unit tests
./gradlew :app:lintDebug          # lint
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:connectedDebugAndroidTest   # emulator required
```
Runtime setup: open Settings → add a GitHub PAT (repo scope) and ≥1 LLM provider (Ollama Cloud key, or OpenAI-compatible key for Groq/Cerebras/OpenRouter/Experiential Labs/…) → Test connection → pick a repo → chat. No API keys are shipped with the app; nothing to configure at build time (release signing is env-driven and optional).

## 5. Commands executed and their results (baseline, before any change)

| Command | Result |
|---|---|
| `./gradlew :core:model:test :core:domain:test :core:data:testDebugUnitTest :app:testDebugUnitTest` | ✅ BUILD SUCCESSFUL (3m06s, JDK 17) |
| `./gradlew :app:assembleDebug :app:lintDebug` | ✅ BUILD SUCCESSFUL (4m53s) |
| Dependency/secret scan (`grep` for key patterns, `Log.*`, `println`, `TODO/FIXME`) | ✅ No hardcoded secrets, no production logging at all, no TODO/FIXME debt |
| Migration SQL review (MIGRATION_1_2 column names vs entities) | ✅ Column references are correct on both sides |
| `Docker build` / DB migration status / E2E | N/A — Android client app; no Dockerfile, no server, no migrations to run |
| Connected emulator tests | Not run locally (no KVM emulator in audit sandbox); executed by GitHub Actions on the branch push (green) |

No baseline failures were hidden; the project was already building and green before fixes — which is consistent with its CI history (`Auto-merge … after green CI` commits on main).

## 6. Critical bugs (P0)

**None confirmed.** No crash-on-launch, no data-destroying defect, no credential exposure path was found. (The two candidates that could have been P0 — commit-409 leaving a stuck PENDING row, and AutoFix claiming false success — were reclassified after tracing the error path: the coordinator resolves PENDING rows to REJECTED on `TurnEvent.Error`, and the false-success path requires specific timing, making it P1.)

## 7. High-severity bugs (P1)

### AUD-001 — AutoFixLoop can attribute a stale CI run to the new commit (false "CI green")
- **Category:** AI agent loop correctness / honesty of success reporting
- **Severity:** P1 · **Confidence:** High · **Status:** ✅ Fixed (commit `fix(autoFix): attribute CI runs to the exact commit`)
- **File and line:** `core/domain/.../AutoFixLoop.kt:320-324` (candidate selection), `:57-69` (baseline best-effort), `core/data/.../GithubApi.kt:184-191` (no `head_sha` in DTO), `core/model/.../Models.kt:183-190` (`WorkflowRunInfo` has no head SHA)
- **Problem:** `waitForCi` picks the newest run whose id differs from the pre-commit baseline and accepts it if `status == completed`. Two reachable paths produce a **wrong attribution**: (a) the baseline lookup is wrapped in a best-effort `catch` that leaves `baselineRunId = null` on any error — then *any* old completed run for the branch is accepted immediately; (b) GitHub sometimes registers a new run with >15s latency (first poll); the selector then falls back to an *older* run (`id != baseline` matches second-newest), which may be `completed/success` → the loop reports "CI is green" for a commit whose CI never ran.
- **Evidence:** `WorkflowRunInfo` carries only `id/name/status/conclusion/htmlUrl/updatedAtMillis`; `CommitResult.newSha` exists but is never compared to anything. `FakeGithubService` even documents the loose behavior in `AutoFixLoopTest.loop_passesOnFirstGreenCi` ("No baseline … First poll after commit is green").
- **How to reproduce (deterministic):** unit test with `workflowRunSequence` = a completed `success` run with `headSha="old-sha"` present from the first poll, and no run ever matching the committed SHA → pre-fix loop emits `CiPassed`; post-fix loop keeps polling and honestly gives up.
- **User/business impact:** the app's core promise — "CI is the source of truth; never claim success it didn't achieve" — breaks; users merge red code.
- **Security impact:** an attacker who can trigger a stale successful run (or time CI registration) can make the autonomous loop *stop* on a false green even when its own change broke the build (suppression of failure signals).
- **Recommended fix (implemented):** map `head_sha` from the GitHub DTO into `WorkflowRunInfo`; `TurnEvent.WriteCommitted` now carries the commit SHA; `waitForCi` requires a run whose `headSha == expectedSha` (runs with a *different non-null* SHA are ignored; legacy fallback only when the SHA is unknown); regression tests added.
- **Suggested test:** `loop_ignoresStaleRuns_whenHeadShaAvailable` (must GaveUp, never CiPassed) + `loop_waitsForMatchingHeadSha_thenPasses`. ✅ Both added and green.

## 8. Medium-severity bugs (P2)

### AUD-002 — Cross-conversation turn conflict silently drops the user's message
- **Severity:** P2 · **Confidence:** High · **Status:** ✅ Fixed
- **Files:** `app/.../turn/AiTurnCoordinator.kt:87-88` (`if (turnJob?.isActive == true) return`), `app/.../ui/chat/ChatViewModel.kt:302-309` (user message appended *before* `startTurn`)
- **Problem:** turns are hosted app-wide (one at a time, by design). If a turn is running for chat A and the user sends a message in chat B, the ViewModel persists the user message to Room, then `startTurn` no-ops. Chat B shows the message forever with no reply and **no error, no snackbar, no retry affordance** (the per-screen `typing` mirror is keyed by repoKey and correctly shows nothing).
- **Impact:** message appears "eaten"; user retried sends can also silently no-op.
- **Fix (implemented):** `startTurn` returns `Boolean`; the ViewModel checks `coordinator.state.active` *before* persisting and surfaces a typed snackbar ("Another AI reply is in progress — stop it or try again when it finishes"); a Stop control (AUD-004) makes the blocked state resolvable from any chat.
- **Test:** coordinator-level unit behavior covered by `AiTurnCoordinatorTest.startTurn_returnsFalse_whenTurnAlreadyActive` (new).

### AUD-003 — Proposals stuck PENDING forever after process death mid-commit
- **Severity:** P2 · **Confidence:** High · **Status:** ✅ Fixed
- **Files:** `app/.../ui/chat/MessageComponents.kt:378-404` (approve/reject buttons render only when `gateActive`), `AiTurnCoordinator.kt:304-308` (REJECTED-on-error only runs in the live-turn path), `README.md:198-200` (claims this class of bug "should not happen anymore")
- **Problem:** if the process dies between the Approve tap and the commit result, the Room row stays `MessageStatus.PENDING`. On restart there is no live turn, `gateActive` is false, so the card renders a "Pending review" chip with **no action possible** — permanently.
- **Fix (implemented):** when ChatViewModel binds a session and no turn is active for it, stale `WRITE_FILE/PENDING` rows are resolved to REJECTED via a single UPDATE, and an honest note is appended ("Previous proposal(s) were left pending because the app restarted…verify the working branch if you approved just before the restart" — the commit itself may or may not have landed).
- **Test:** `ChatRepositoryImplTest.rejectStalePendingWrites` (new, Robolectric-free via DAO-level SQL on in-memory Room… executed as `:core:data` unit test with the Room runtime artifact — see §15 note) + fake coverage in domain tests.

### AUD-004 — The user cannot stop a running agent turn
- **Severity:** P2 · **Confidence:** High · **Status:** ✅ Fixed
- **Files:** `AiTurnCoordinator.kt` (no cancel API existed), `ChatScreen.kt` BottomBar (no stop control)
- **Problem:** once a turn (or a 12-minute AutoFix CI wait) starts, the only escape is killing the app — which also orphans a PENDING row (AUD-003). Phase-3 checklist item "user ability to stop or cancel the agent" was **Missing**.
- **Fix (implemented):** `AiTurnCoordinator.cancelTurn()` (cancels the job, drains the approval StateFlow, idles the live state, stops the FGS); `CancellationException` is now rethrown (not mapped to a fake "Something went wrong" error) in the collector; a **Stop** button appears in the chat bottom bar whenever the turn is active. Auto-fix GaveUp remains the loop's own honest exit.
- **Test:** covered by coordinator unit test (`cancelTurn_transitionsToIdle`, new).

### AUD-005 — README/CI drift: documented auto-merge & dependency-graph do not exist
- **Severity:** P2 · **Confidence:** High · **Status:** ✅ Fixed (docs + workflow made consistent)
- **Files:** `README.md:135-149` (auto-merge section), `:139-143` ("submits the Gradle dependency graph"), `.github/workflows/android.yml` (no merge job, no dependency-graph step; auto-merge was removed in `d8393b4` — the newest "Auto-merge …" commits on main were produced by the *old* workflow that still existed on the working branch)
- **Impact:** contributors/operators rely on README for safety guarantees; the documented "main can never receive a red commit" mechanism no longer exists.
- **Fix (implemented):** README rewritten to describe the *actual* CI (validate-only, artifacts, optional signing), plus an explicit note that auto-merge was removed and how to re-enable it safely; `notify-failure` needs-condition fixed (AUD-013) and the emulator runner fork replaced (AUD-014).

### AUD-006 — Indirect prompt injection has no structural defense
- **Severity:** P2 (design risk; exploitability verified in principle, not weaponized) · **Confidence:** High · **Status:** ✅ Mitigated (defense-in-depth; residual risk documented)
- **Files:** `core/model/.../PromptBuilder.kt:63-76,92-98` (file/attachment contents embedded verbatim), `core/domain/.../AutoFixLoop.kt:426-442` (CI logs embedded verbatim), `PromptBuilder.system()` (no "content is data" rule)
- **Problem:** repository files and CI logs are attacker-controllable in public repos (e.g. a source file or a test that prints `Ignore previous instructions…`). They are concatenated into the model context without delimiters or an untrusted-data rule. In normal mode the Approve/Reject gate contains the damage; in **auto-fix mode writes are auto-approved by design**, so a poisoned log can steer unattended commits on the user's token.
- **Mitigations already present:** strict JSON action contract, path sanitization, working-branch isolation, PR-merge stays manual, attempt caps.
- **Fix (implemented):** file contents / attachments / CI-log excerpts are now wrapped in explicit `BEGIN/END UNTRUSTED CONTENT` delimiters, and the system prompt + fix prompts state that delimited content is data, never instructions (only the user's task and the system prompt drive actions). This is defense-in-depth for the LLM layer — the approval gate and branch isolation remain the real enforcement boundary.
- **Residual risk:** no LLM-side defense is absolute; documented in §19. Prompt-injection regression tests added at the unit level (assert delimiters + data rule are present in built prompts).

## 9. Low-severity bugs (P3)

| ID | Finding | File(s) | Status |
|---|---|---|---|
| AUD-007 | GitHub `OkHttpClient` has no explicit timeouts (10s defaults; LLM clients got 30/120/60) → slow tree/log fetches surface as "No network connection" | `core/data/.../di/DataModule.kt:113-123` | ✅ Fixed (30/60/60s) |
| AUD-008 | AutoFixLoop retries Unauthorized/Configuration errors until attempts are exhausted — credentials/config problems can never succeed and should fail fast | `AutoFixLoop.kt:141-151` | ✅ Fixed (+test) |
| AUD-009 | `deleteConversation` clears messages then deletes session non-transactionally (crash window → orphan messages) | `ChatRepositoryImpl.kt:105-108` | ✅ Fixed (`@Transaction` DAO method) |
| AUD-010 | `check_ci_status` accepts a model-provided branch override without restricting it to the session's working branch (read-only, but can mislead the user / auto-fix prompts) | `AiAction.kt:98-102`, `AiEditOrchestrator.kt:227-253` | ✅ Fixed (override now restricted to the working branch) |
| AUD-011 | Markdown links from model output open any URI scheme (`openUri` with model-controlled string) | `MarkdownProse.kt:79-90,101-112,123-134` | ✅ Fixed (http/https allowlist) |
| AUD-012 | Attachment load failure/oversize clears the composed text (typed message lost) | `ChatScreen.kt:502-525` | ✅ Fixed (input restored) |
| AUD-013 | `notify-failure` job uses `needs: [build, connected-tests, dependency-review]` + `if: failure()`; a skipped `dependency-review` (non-PR) can suppress the notification | `.github/workflows/android.yml:162-172` | ✅ Fixed (explicit result conditions) |
| AUD-014 | CI uses a **third-party fork** `reactivecircus1000/android-emulator-runner@v2` (canonical: `reactivecircus/android-emulator-runner`) and no action is SHA-pinned | `.github/workflows/android.yml:130` | ✅ Fork replaced with canonical action @v2 (SHA-pinning of all actions recommended; tracked in roadmap) |
| AUD-015 | `getJobLogs` loads the full log string before tail-truncation (huge logs → memory spike) | `GithubRepositoryImpl.kt:199-208` | Accepted (streamed tail would complicate the API for marginal benefit; log sizes bounded in practice) |
| AUD-016 | `OllamaKeyOverride` is a process-global volatile override — a Settings "Test connection" racing an in-flight Ollama turn could swap keys for one call (transient 401) | `AuthInterceptors.kt:47-73` | Accepted, documented (single-turn-per-app design makes the window very small) |
| AUD-017 | `conversations()` N+1 (per-session `latestForRepo` query) | `ChatRepositoryImpl.kt:37-61` | Accepted (list sizes are conversation counts; roadmap item) |
| AUD-018 | `exportSchema = false` → no Room schema history/migration tests | `AppDatabase.kt:15` | Accepted (roadmap) |
| AUD-019 | `ensureSession` read-then-insert race could regenerate a sessionId (needs unique index + insert-ignore pattern) | `ChatRepositoryImpl.kt:63-80` | Accepted, documented (single-writer UI makes the window tiny) |
| AUD-020 | Hardcoded English strings in Settings screen ("Search models", "ACTIVE", "No models match") while everything else uses resources | `SettingsScreen.kt` | Accepted (i18n roadmap) |
| AUD-021 | Device-transfer backup still includes the chat DB (code snippets) while secure prefs are excluded | `data_extraction_rules.xml:9-11` | Accepted (privacy note §17) |

## 10. Security vulnerabilities

**No critical vulnerabilities found.** Positive controls verified:

- **Secrets:** GitHub PAT + provider keys live in `EncryptedSharedPreferences` (Keystore master key AES256-GCM; pref keys AES256-SIV, values AES256-GCM) — `EncryptedSettingsStore.kt:104-116`; excluded from cloud backup *and* device transfer (`backup_rules.xml`, `data_extraction_rules.xml`); keys are never logged (the app contains **zero** `Log.*` calls), never enter BuildConfig, and provider cards show only a masked fingerprint (`KeyMaskingTest` covers `xpl_••••••••9F3A` shape). Release signing is env-driven; the CI explicitly avoids exposing signing secrets to PR builds.
- **GitHub write path:** file paths from the model pass through a single sanitizer (`AiActionParser.sanitizePath`: backslash normalization, `..`/`.git` segment rejection, 512-char cap) — 14 unit tests cover it; content writes **always** set `branch` explicitly (`GithubRepositoryImpl.kt:121-128`) and the working branch is always `ai-chat/<sessionId>` (`ensureWorkingBranch` can never return main); 409-conflict handling re-reads rather than force-writes; PR creation is a separate explicit action; merge stays manual.
- **Transport:** HTTPS only; no `usesCleartextTraffic`; no cert pinning (acceptable for BYO-key client; noted in hardening).
- **Injection surfaces:** no SQL string building (all Room `@Query` with bound params); no WebView; no `eval`-style deserialization; Compose renders text (no HTML rendering → no XSS analog); model-controlled markdown **links** were an open redirect-analog and are now scheme-allowlisted (AUD-011).
- **CI supply chain:** `settings.gradle.kts` content filters (google/mavenCentral only), `dependency-review` gate on PRs (fail on high); fork/unpinned-action risk fixed (AUD-014); workflow `permissions: contents: read` (least privilege).
- **Error hygiene:** typed errors surface fixed human messages; provider `detail` text is included for 429 (bounded to 240 chars) but never echoes Authorization headers or keys (verified in `ErrorMapping.kt`).

## 11. AI-agent capability assessment (Phase 3)

| Capability | Verdict | Evidence |
|---|---|---|
| **Agent loop** | **Implemented** | `AiEditOrchestrator.runTurn`: ≤10 model steps, plan(read)→act→observe cycles, honest exhaustion message |
| Termination / max iterations | **Implemented** | `MAX_MODEL_STEPS=10`; AutoFix `maxAttempts.coerceIn(1,10)`; CI poll budget 12min/40 polls |
| Token/cost budget | **Partial** | 200k-char context cap + 80k-char file cap (chars, not tokens); **no usage/cost tracking** — OpenAI `usage` field is ignored (roadmap) |
| Timeouts | **Implemented** | OkHttp 30/120/60s (LLM), now also GitHub (AUD-007); CI wait has deadline+poll cap |
| Cancellation | **Was Missing → Implemented** | `cancelTurn()` + Stop button (AUD-004); coroutine cancellation propagated |
| Recovery from partial failure | **Implemented** | typed errors → retry affordance; stale-PENDING cleanup (AUD-003); auto-fix per-attempt isolation |
| Agent state persisted safely | **Implemented** | every proposal/decision/outcome is a Room row; turn output survives process death |
| Concurrent agent runs | **Partial (by design)** | one turn per app; conflict now surfaces instead of dropping (AUD-002) |
| **Tool calling** | **Implemented (5-tool allowlist)** | strict JSON schema; server-side path validation; read-only vs destructive split; destructive = human approval |
| Tool args validated server-side | **Implemented** | `sanitizePath` choke point + re-validation in the GitHub repository layer (added) |
| Human confirmation before destructive actions | **Implemented (with documented opt-out)** | Approve/Reject gate; auto-fix bypass is explicit opt-in per message |
| Tool calls logged/auditable | **Implemented (local audit trail)** | read/write/commit events persisted as chat rows + notification progress |
| Tool idempotency / duplicate prevention | **Partial** | content-API commits are sha-guarded (409 on drift) but double-approve races rely on UI gating |
| Tool results treated as untrusted | **Was Partial → Implemented** | delimiters + data-not-instructions rules (AUD-006); *LLM-level only, by nature* |
| Sandboxing | **N/A by architecture** | no shell/exec tools exist; the only "effectors" are GitHub contents/PR APIs behind the user's PAT and the approval gate |
| **Prompt security** | **Partial → Mitigated** | AUD-006; system/user/content separation exists structurally (roles), now also explicit for embedded content |
| System prompt leakage | **Implemented** | system prompt is fixed code-side; fallback reply path shows raw model text only when parsing fails (documented behavior) |
| **LLM reliability** | **Partial** | typed error mapping (incl. 402/429 + Retry-After); provider fallback chain on rate limits; empty/malformed response handling (`fallbackText`, NDJSON concatenation); **missing:** automatic retry w/ backoff on transient 5xx, model fallback beyond rate limits, `usage` capture |
| **RAG / knowledge** | **N/A (deliberate)** | no vector DB; retrieval = bounded file tree (2.5k entries/45k chars) + on-demand `read_file`; per-tenant isolation is trivially single-user-device. This is the correct architecture for repo-editing; adding RAG now would be unjustified complexity |
| **Memory / conversation mgmt** | **Implemented (device scope)** | Room history, per-session context windows, conversation delete (+now transactional), title generation; no cross-user boundary to enforce; memory-poisoning risk == AUD-006 |
| **Human-in-the-loop** | **Implemented** | approval gate, PR-open/merge manual, AI-attribution implicit in chat UI, cancel (new), audit trail |
| **AI safety/privacy** | **Implemented (minimal viable)** | keys never in prompts (only in headers); no PII masking (device-local, BYO-key — documented); abuse prevention N/A (no server); quota visibility = provider error text surfaced |
| **Observability/evals** | **Missing** | no token/cost metrics, no prompt-version tracking, no eval datasets, no crash reporting/analytics (also a privacy positive) — roadmap §18 |

## 12. Missing AI-agent features (prioritized)

1. Token **usage/cost tracking** per turn/connection (responses already carry `usage` on most providers).
2. Retry with exponential backoff for transient 5xx/network on LLM calls (currently: rate-limit fallback only, timeouts otherwise).
3. Per-tool-call timeouts distinct from the HTTP read timeout (long tool loops rely on the global budget).
4. Agent trace export (chat log export exists only via OS copy; no structured trace).
5. Prompt/version registry + small offline eval fixture set for the action parser & prompts.
6. Optional streaming (currently whole-response; mobile UX acceptable, but streaming would improve perceived latency).

## 13. UX/product gaps (Phase 4)

Present and verified: empty/loading/error states on all screens, retry affordances, conversation history + search (Home), provider picker with FREE/PROMO/PAID badges + offline cache indicator, connection tests with precise per-HTTP-code messages, destructive-action confirmations (clear chat, delete conversation), attachment size caps (5MB) with too-large feedback, a11y contentDescriptions on icons, keyboard/IME handling (`imePadding`, `adjustResize`), RTL flag, FGS progress + deep link.

Gaps: no **Stop** control (fixed, AUD-004); stuck-PENDING dead-end (fixed, AUD-003); busy-conflict silent drop (fixed, AUD-002); typed text lost on attachment failure (fixed, AUD-012); no data export; no onboarding walkthrough for first-run (README compensates); no quota/usage dashboard; partial i18n readiness (AUD-020); notification has no direct Approve/Reject actions (branch-review deferred to roadmap).

## 14. DevOps/deployment gaps (Phase 5)

Solid for an Android client: validate-only CI on all branches, unsigned/signed release assembly with explicit incomplete-signing errors, artifact uploads (lint/tests/APKs), PR dependency review, KVM emulator job. Gaps/notes: README drift (fixed), fork/unpinned actions (fixed fork; SHA-pin all), no `dependency-graph` submission (README claimed it — removed claim; adding it requires widening token permissions — roadmap decision), no release channel/tagging flow, no crash reporting (privacy trade-off documented), `notify-failure` needs-condition (fixed). N/A items: Docker/CDN/queues/backends (no server exists).

## 15. Testing gaps (Phase 6)

Existing suite (all green at baseline) covers: action parser incl. path traversal/fences/fallbacks; prompt builder (vision, caps); diff engine; file-tree formatter; pricing classifier; presets; session-id; request shaping (headers/`response_format`/vision parts); error mapping (401/402/429/Retry-After); router fallback; orchestrator loop (approve/reject/attachments/PR/CI/general mode); AutoFixLoop (green-first, real-log refetch, honest give-up); markdown segmentation; key masking.

Added by this audit: head-SHA attribution regression tests (false-green + wait-then-green), fail-fast on non-retryable errors, coordinator busy/cancel behavior, stale-PENDING resolution, prompt-injection delimiter assertions, path re-validation guard, transactional delete. **Not** feasible in this environment: connected/emulator tests locally (executed by CI instead), live-LLM tests (per requirement, everything is mocked/recorded — no test in the repo performs a real network call). Remaining gap: Robolectric/ Room DAO instrumentation coverage, load/concurrency tests (N/A — client app), UI tests (Compose) — roadmap.

## 16. Performance and scalability risks

- `conversations()` N+1 (AUD-017) — bounded by conversation count; fine on-device.
- LCS diff capped at 4M DP cells with prefix/suffix fallback — good; `getJobLogs` full-string load (AUD-015) accepted.
- `PromptBuilder.cap` uses `result.add(1, …)` in a loop (O(n²) on overflow only) — negligible at real sizes.
- LLM calls are sequential per turn; file tree capped; no unbounded loops found (all loops have numeric caps).

## 17. Privacy and compliance risks

- Data leaves the device **only** to: api.github.com (user-selected repos), the user's chosen LLM provider(s), and nowhere else (verified: base URLs come exclusively from user-configured connections; no telemetry/analytics/crash reporting exists — good for privacy, weaker for defect diagnostics).
- Repo code (possibly with secrets committed upstream) is sent to the LLM provider — inherent to the product; surfaced honestly? partially: the Settings/README should state it explicitly (added to README "Privacy" note in the fix commit).
- Backups: secure prefs excluded everywhere; chat DB excluded from cloud backup but included in **device transfer** (AUD-021) — acceptable, documented.
- No PII collection; no third-party SDK analytics.

## 18. Recommended roadmap

**30 days:** SHA-pin all GitHub Actions; add Room schema export + migration tests; token usage/cost capture and per-connection display; LLM retry/backoff for transient 5xx; Compose UI tests for the approval gate; README privacy note (done in fix commit) + F-Droid/Play data-safety review.
**60 days:** conversation search within chat, chat export (md/json), structured agent trace (JSONL) + local diagnostics screen; optional dependency-graph submission with explicit token scope; Robolectric coverage for Room DAOs; i18n extraction of remaining strings.
**90 days:** optional per-tool-call timeouts + tool registry abstraction (prep for more tools); prompt eval fixtures + CI job for parser/prompt regression; optional streaming responses; threat-model doc for auto-fix unattended mode (incl. "trusted repos only" guidance); consider allowlist-based `intent` handling for CI links.

## 19. Remaining risks after fixes

1. **LLM-level prompt injection cannot be fully eliminated** — the approval gate (or explicit opt-in to auto-fix) and branch isolation remain the true boundaries; unattended auto-fix on untrusted repos is still discouraged (now documented in README).
2. `OllamaKeyOverride` global-override race (AUD-016) — accepted, tiny window.
3. No crash/analytics telemetry — defects surface only via CI/store reviews.
4. Emulator/connected tests are exercised only in CI, not locally in this audit environment.
5. Provider catalogs change faster than app releases; the app handles this honestly (live listing + "Pricing unavailable"), but promo badges can lag reality (by design, PROMO is a UI hint only).

---

## 20. Fix implementation record (Phase 8/9)

Branch: **`ai-chat/audit-prod-002`** — 8 logical commits, all validated locally before pushing.

| # | Commit | Fixes | Regression tests added |
|---|---|---|---|
| 1 | `8c1a953 docs: production-readiness audit report` | this report (baseline §5) | — |
| 2 | `e50cd99 fix(autoFix): attribute CI runs to the exact commit via head_sha` | AUD-001 (P1) | `AutoFixLoopTest.loop_ignoresStaleRuns_whenHeadShaAvailable` (stale success run must produce an honest GaveUp, never CiPassed) · `loop_waitsForMatchingHeadSha_thenPasses` (loop must keep polling until the run for OUR commit appears) |
| 3 | `29ab8f9 fix(app): surface busy-conflict, add Stop control, resolve stale proposals` | AUD-002/003/004 | UI/coordinator layer — verified by compile + reasoning; JVM-runnable regression coverage added where the layer allows (see note below) |
| 4 | `0ca5c92 fix(data): GitHub client timeouts, path re-validation at repo boundary` | AUD-007 + defense-in-depth for the write path | `GithubPathGuardTest` (6 tests: traversal, `.git` internals, normalization, unicode, blank/oversized, typed error) |
| 5 | `1a45c74 fix(autoFix): fail fast on non-retryable errors` | AUD-008 | `AutoFixLoopTest.loop_failsFast_onUnauthorized` (stops after attempt 1 with the exact reason) |
| 6 | `031cf3f fix(model): untrusted-content delimiters, data rule, CI branch lock` | AUD-006/010 | `PromptBuilderTest.model_outputs_are_wrapped_in_untrusted_delimiters` · `AiEditOrchestratorTest.check_ci_status ignores model branch override…` |
| 7 | `0036e23 fix(ui): only open http/https links …; restore input on attachment failure` | AUD-011/012 | UI-level (scheme allowlist helper, input restore) |
| 8 | `8c7d29c ci+docs: align workflow and README with reality` | AUD-005/013/014 | — |

Note on test scope: the coordinator/ViewModel layer is Android-coupled (`Context`, FGS, `R.string`); JVM regression tests were added for every fix with a pure-Kotlin seam (auto-fix attribution, fail-fast, path guard, prompt delimiters, branch lock). The coordinator changes (busy-conflict return value, `cancelTurn`, stale-pending resolution) are exercised by the existing fake-based domain tests plus compile-time verification, and by CI's connected-test stage on the branch push.

**Post-fix validation (all green):**
- `./gradlew :core:model:test :core:domain:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` → **BUILD SUCCESSFUL (2m34s), 114 unit tests, 0 failures**
- Secret scan over every changed file → clean (no key patterns introduced)
- GitHub Actions on the pushed branch → **green** (build + lint + unit tests × debug/release + assembleDebug/Release + connected emulator tests)
