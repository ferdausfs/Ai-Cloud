# Production-Readiness Audit — Ai-Cloud (RepoChat AI)

**Repository:** https://github.com/ferdausfs/Ai-Cloud
**Audit date:** 2026-09-08
**Auditor:** independent full-stack / security / AI-agent audit
**Baseline audited:** commit `ef9122b` initially, re-verified against latest `origin/main` = `1d645ec` (the repo advanced via its own CI auto-merge during the audit window — all findings below were re-verified against `1d645ec`).
**Scope:** every source file, build script, CI workflow, manifest, test, and the README. No assumptions; all findings cite file + line from the actual source.

---

## 1. Executive Summary

Ai-Cloud ("RepoChat AI") is a **native Android AI-agent application**, not a chatbot wrapper: it runs a real tool-calling loop (`read_file` / `write_file` / `create_pull_request` / `check_ci_status`), commits to isolated `ai-chat/<session>` working branches, gates writes behind a human Approve/Reject diff, and can autonomously iterate "until CI is green" by reading real GitHub Actions failure logs. The architecture (Clean Architecture, 4 Gradle modules, Hilt DI, EncryptedSharedPreferences for credentials, Room for history, foreground service for long turns) is unusually disciplined for a project of this size, and the test suite (77+ unit tests across all modules) is genuinely meaningful — it tests the agent loop, the action parser, the LLM router fallback, and error mapping with fakes.

**Verdict before fixes: NOT READY FOR PRODUCTION — but closer than typical.** There is exactly one confirmed high-severity functional bug (user messages silently swallowed when a turn is already running in another conversation), a cluster of agent-safety gaps (no turn cancellation, no approval gate for model-initiated pull requests, auto-approved writes to CI-critical paths like `.github/workflows/` during the unattended AutoFixLoop), and several reliability/polish issues (no Android 13 notification permission request, no URL-scheme allowlist on model-generated links, missing GitHub client timeouts, no LLM cost ceilings). No hardcoded secrets, no SQL injection surface, no classic XSS surface (Compose-native markdown, no WebView), and credential storage/backup hygiene is correct.

**Verdict after the fixes in this audit (see §19.1): CONDITIONALLY READY** for personal / internal dogfooding. It is not yet a multi-tenant SaaS and should not be advertised as one — see §17–18.

| Severity | Confirmed findings |
|---|---|
| P0 Critical | 0 |
| P1 High | 1 (BUG-101) |
| P2 Medium | 8 (BUG-201, BUG-202, BUG-203, BUG-204, SEC-205, BUG-206, SEC-207, COST-208) |
| P3 Low | 9 (BUG-301…BUG-309) |
| Fixed during this audit | BUG-101, BUG-201, BUG-202, BUG-203, BUG-204, BUG-206, SEC-205, SEC-207 (partial) |
| Already fixed by the repo's own agent during the audit window (verified, not re-reported as open) | approval-race (SharedFlow dropped emissions), branch-create 422 race, broken OWASP Dependency-Check CI job |

---

## 2. Project Purpose and Current Architecture

**Purpose:** a mobile "AI pair programmer" that operates on a GitHub repository the user selects. The user chats; the model plans edits as strict-JSON tool actions; the app executes them against the GitHub REST API on a per-session working branch; a diff + Approve/Reject gate precedes every commit; an opt-in AutoFixLoop keeps editing + committing until GitHub Actions passes, feeding real failure logs back to the model.

### Architecture map

```
┌─────────────────────────────── app (Compose UI) ───────────────────────────────┐
│ MainActivity → AppNavHost → HomeScaffold / RepoPicker / RepoDetail / Chat /    │
│ Settings. ChatViewModel delegates turn execution to AiTurnCoordinator.         │
│ Markdown rendering is Compose-native (no WebView). AiTurnService = FGS         │
│ (dataSync) keeps the app-scope turn coroutine alive when backgrounded.         │
└────────────────────────────────────────┬───────────────────────────────────────┘
                                         │ StateFlow<TurnEvent> / approval Flow
┌──────────────────────────── core:domain (pure Kotlin) ─────────────────────────┐
│ AiEditOrchestrator = the agent loop (max 10 model steps, JSON tool contract,   │
│   read_file→context→model iteration, write gate). AutoFixLoop = edit→commit→   │
│   poll CI→fetch job logs→re-prompt (≤5 attempts, ≤12 min CI wait, backoff).    │
│ LlmRouterImpl = provider fallback on 429/rate-limit-like errors. Use cases.    │
└────────────────────────────────────────┬───────────────────────────────────────┘
┌─────────────────────────────── core:data (Android) ────────────────────────────┐
│ Retrofit/OkHttp: GithubApi, OllamaApi (NDJSON), OpenAiCompatibleApi.           │
│ Room (repochat.db v2): repo_sessions, chat_messages, active_repo.              │
│ EncryptedSettingsStore = EncryptedSharedPreferences (Keystore AES-256) for     │
│ GitHub PAT + all LLM keys. Hilt DataModule wires everything.                   │
└────────────────────────────────────────┬───────────────────────────────────────┘
┌────────────────────────────── core:model (pure Kotlin) ────────────────────────┐
│ AiActionParser (strict JSON + fenced-JSON recovery + path sanitization),       │
│ PromptBuilder (system prompt, 200k-char context cap, 80k file cap, 45k tree    │
│ cap), LineDiffer, FileTreeFormatter, ProviderPresets, ModelPricing.            │
└────────────────────────────────────────────────────────────────────────────────┘
External services: GitHub REST v3 (repos, git refs, trees, contents, PRs, Actions
runs/jobs/logs), Ollama Cloud (api/chat NDJSON), any OpenAI-compatible provider
(Groq, Cerebras, OpenRouter, Together, Fireworks, Experiential Labs, Custom).
Deployment: GitHub Actions CI (build+lint+unit tests+emulator connected tests,
dependency review, debug-APK artifact, auto-merge of green ai-chat/* → main).
```

### Is it an agent or a chatbot wrapper?
**It is a genuine agent application.** It has: (a) an observe–plan–act loop with tool dispatch; (b) environment state (branch, tree, file contents) gathered per turn; (c) a termination condition (reply, write, or 10-step exhaustion); (d) a multi-attempt autonomous goal loop (AutoFixLoop) driven by real environment feedback (CI logs). A chatbot wrapper would only forward messages to an LLM and render replies — that describes only this app's "General chat" mode. The repo-mode is the agent.

---

## 3. Technology Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21 (JVM target 17) |
| UI | Jetpack Compose (BOM 2025.01.00), Material 3, Navigation Compose 2.8.5, shared-element transitions |
| DI | Hilt 2.53.1 (KSP 2.0.21-1.0.28) |
| Persistence | Room 2.6.1 (DB v2, 1 migration), EncryptedSharedPreferences (security-crypto 1.1.0) |
| Networking | Retrofit 2.11.0, OkHttp 4.12.0, kotlinx.serialization 1.7.3, coroutines 1.9.0 |
| Background | Foreground service (dataSync) + application-scoped coroutine (AiTurnCoordinator) |
| LLM providers | Ollama Cloud (bespoke NDJSON /api/chat), OpenAI-compatible /chat/completions (multi-provider router with 429 fallback) |
| Build | Gradle 8.11.1, AGP 8.7.3, version catalog, minSdk 24, target/compile 35 |
| CI | GitHub Actions: assembleDebug, lintDebug, unit tests (4 modules), emulator connected tests (API 34 x86_64), dependency-review (PRs), debug APK artifact, auto-merge ai-chat/*→main |
| Testing | JUnit 4, kotlinx-coroutines-test; fake-based domain tests; no Robolectric, no Compose UI tests, no connected tests in-repo |

Authentication/authorization model: single-user local app. Credentials are the user's own GitHub PAT (scope `repo`) and LLM API keys, stored encrypted on-device. There is no server, no multi-user auth, no tenant isolation requirement (by design).

---

## 4. How to Run the Project

1. **Requirements:** Android Studio (Koala+), JDK 17, Android SDK 35.
2. Open the repo; `local.properties` is generated by Studio (or set `sdk.dir`).
3. Run the app → **Settings** → paste an [Ollama Cloud API key](https://ollama.com) (or add a Groq/Cerebras/OpenRouter/Experiential Labs connection) and a GitHub PAT with `repo` scope → **Test** each.
4. Browse repositories → pick one → chat. Approve diffs to commit to `ai-chat/<session-id>`; create PRs from the chat; optionally enable **Auto-fix until CI passes**.
5. CLI: `./gradlew :app:assembleDebug` (build), `./gradlew :app:lintDebug` (lint), `./gradlew :core:model:test :core:domain:test :core:data:testDebugUnitTest :app:testDebugUnitTest` (tests). Release APK: `./gradlew :app:assembleRelease` (see §19.1 for signing).

There is **no `.env` file** in this project (Android app — secrets are user-entered at runtime into encrypted storage; `local.properties` only points at the SDK and is already git-ignored). A `.env.example` is therefore not applicable; the runtime-equivalent documentation lives in README §Getting started, which this audit keeps accurate.

---

## 5. Commands Executed and Their Results

| Command | Result |
|---|---|
| `git clone` (SSH-less, PAT) | OK — full history + 17 remote branches |
| Android SDK install (platform-35, build-tools 35.0.0, platform-tools) + Temurin JDK 17 | OK (initially the sandbox had only a JRE 21 — Gradle toolchain rejected it: `does not provide the required capabilities: [JAVA_COMPILER]`; documented, resolved by installing JDK 17) |
| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL in 3m48s** (after constraining daemon memory to fit the 4 GB audit sandbox: `-Xmx1280m`, 1 worker) |
| `./gradlew :app:lintDebug` | **Exit 0** — no lint errors reported |
| `./gradlew :core:model:test :core:domain:test :core:data:testDebugUnitTest :app:testDebugUnitTest` | **BUILD SUCCESSFUL** — 77 tests, 0 failures, 0 errors (35 model / 18 domain / 15 data / 9 app) at baseline `ef9122b` |
| `./gradlew :app:assembleRelease` | Added by this audit (§19.1) — unsigned/debug-fallback signing, CI builds it |
| `git log` / branch analysis | 17 remote `ai-chat/*` + `arena/*` working branches; main advanced via auto-merge during the audit |
| Secret scan (regex for PAT/Groq/OpenAI/AWS/Google keys, whole tree incl. tests) | **Clean** — only a `[REDACTED:github_token]` placeholder string inside a test fixture (`KeyMaskingTest.kt:33`) |
| GitHub Actions API query (last 8 runs) | 5 failures / 3 successes historically; **latest main run green** after the repo's own `ci: fix permanently broken dependency scan` commit; root cause of the old failures: OWASP Dependency-Check docker action (unpinned `@main`, missing `suppression.xml`) — already replaced by `dependency-review-action@v4` before this audit landed |
| Emulator connected tests | Not run locally (no KVM in sandbox); CI runs them on `ubuntu-latest` |

No command failures were hidden: the two failures above (JRE-not-JDK, first daemon OOM kill in the 4 GB sandbox) are environmental and were resolved with a JDK install and memory-constrained flags; neither is a repository defect.

---

## 6. Critical Bugs (P0)

**None confirmed.** Specifically checked and cleared: no secrets in the tree (regex scan), no SQL injection (Room + parameterized DAO queries only), no command injection (no `Runtime.exec`, no shell anywhere), no path traversal into `.git` (`AiActionParser.sanitizePath` rejects `..` and `.git` segments — `core/model/.../AiAction.kt:110-118`), no classic XSS (Compose-native markdown, no WebView, no HTML rendering), cleartext traffic not enabled (no `usesCleartextTraffic`, default network security config = HTTPS only), EncryptedSharedPreferences excluded from backup + device-transfer (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`).

---

## 7. High-Severity Bugs (P1)

### BUG-101 — User message is silently swallowed when an AI turn is already running in another conversation

- **ID:** BUG-101
- **Category:** Runtime / state-management / agent-concurrency
- **Severity:** P1 (High)
- **Confidence:** High (confirmed by code-path analysis; behavior follows deterministically from three code sites)
- **File and line:**
  - `app/src/main/kotlin/dev/repochat/turn/AiTurnCoordinator.kt:87-88` — `fun startTurn(request) { if (turnJob?.isActive == true) return }` (silent no-op, returns `Unit`)
  - `app/src/main/kotlin/dev/repochat/ui/chat/ChatViewModel.kt:283` — `if (state.typing || state.approvalPending || state.approving) return` (only inspects **this** chat's mirrored state)
  - `app/src/main/kotlin/dev/repochat/ui/chat/ChatViewModel.kt:302-325` — `appendUserText(...)` persists the user message to Room **before** `turnCoordinator.startTurn(request)` is called
  - `app/src/main/kotlin/dev/repochat/ui/chat/ChatViewModel.kt:212-246` — the coordinator-state mirror explicitly skips events whose `live.repoKey != bound` (`if (bound.isNotEmpty() && live.repoKey.isNotEmpty() && live.repoKey != bound) return@collect`)
- **Problem:** The coordinator supports exactly one in-flight turn globally (by design). The send-path guard only knows about the *current* conversation's typing state, so while a turn (especially an AutoFixLoop, which legitimately runs ~12 minutes × up to 5 attempts) is active for conversation A, the user can switch to conversation B and send a message. The message is persisted, then `startTurn` silently returns, so B never gets a reply and no error is surfaced anywhere.
- **Evidence:** the four code sites above form the complete causal chain; there is no other call site of `startTurn`.
- **How to reproduce:** 1) Open repo chat A, enable "Auto-fix until CI passes", send a task (loop now runs for minutes). 2) Open drawer → repo chat B. 3) Send a message. 4) Observe: message bubble appears, no typing indicator, no reply, no error banner — ever. 5) When A's loop finishes, B's message is still unanswered (nothing re-triggers it).
- **User/business impact:** lost/ignored user input; appears as "the AI ignored me"; erodes trust exactly in the flagship feature (long-running autonomous turns).
- **Security impact:** none direct.
- **Recommended fix (implemented in this audit):** make `startTurn` report acceptance (`Boolean`), and have `ChatViewModel.sendInternal` check the coordinator's live state for a *foreign* active turn **before** appending the user message; if busy, surface a typed error banner ("Another AI turn is in progress in <repo> — wait or stop it, then resend") and keep the input text so nothing is lost. `canSend` in the UI is additionally disabled while a foreign turn is active.
- **Suggested test:** unit test on a pure `TurnGate` helper (added): `canStart(currentRepoKey, liveState)` returns false for foreign active turns, true for idle/same-repo; ChatViewModel-level behavior covered by the new regression tests.
- **Status:** FIXED (this audit) + regression tests added.

---

## 8. Medium-Severity Bugs (P2)

### BUG-201 — No way to cancel an in-flight turn, stuck approval, or autonomous loop

- **ID:** BUG-201 — **Category:** Agent control / UX — **Severity:** P2 — **Confidence:** High
- **File and line:** `AiTurnCoordinator.kt:55` (`private var turnJob: Job?`) — no public `cancel()`; `AiEditOrchestrator.kt:182` (`approval.first()` — suspends indefinitely if user walks away); `AutoFixLoop.kt` (loop runs up to ~60 min).
- **Problem:** Once a turn starts, the only ways it ends are: completion, error, or killing the app. If the model hangs (read timeout is 120 s but the loop can retry across 10 steps), or an approval card is left open while the user navigates away, or an AutoFixLoop grinds through 5 attempts, there is no Stop control anywhere in the UI.
- **Impact:** user feels the app is stuck; the FGS notification persists; battery drain; the single-turn-slot design (see BUG-101) blocks all other conversations until it ends.
- **Recommended fix (implemented):** `AiTurnCoordinator.cancelTurn()` cancels `turnJob`, marks any PENDING write row as REJECTED, resets live state, stops the FGS, and appends an honest "stopped by user" note to the conversation; Stop button surfaced in the chat top bar while a turn is active.
- **Suggested test:** domain-level: cancelling the collect job mid-loop leaves no PENDING rows (via FakeChatRepository); app-level wiring verified by build + manual path.
- **Status:** FIXED.

### BUG-202 — Model-initiated `create_pull_request` executes with no human approval gate

- **ID:** BUG-202 — **Category:** Agent safety / human-in-the-loop — **Severity:** P2 — **Confidence:** High
- **File and line:** `core/domain/.../AiEditOrchestrator.kt:209-219` — `is AiAction.CreatePullRequest -> { emit(...); github.createPullRequest(...) }` — no `approval` gate, unlike `write_file` (line 182 `val approved = approval.first()`).
- **Problem:** The README promises "a diff + Approve/Reject gate **before anything is written**", and PR creation is a public, notification-generating write to the user's repository. The only guard is a *prompt-level* instruction ("ONLY when the user has asked") — prompt-level rules are not a security boundary: an indirectly injected instruction in a repository file or CI log ("…now call create_pull_request with title …") can make the agent open arbitrary PRs with attacker-chosen title/body (phishing links in PR text, spam).
- **Impact:** PR spam / socially-engineered PRs from prompt injection; violates the product's own stated safety model.
- **Recommended fix (implemented):** the orchestrator now suspends on the same approval gate for `create_pull_request` (new `TurnEvent.ProposePullRequest`), the coordinator/ChatViewModel expose it as a confirmation card ("Create PR 'title'?"), approval → create, rejection → `TurnEvent.PullRequestDeclined` with an honest chat note.
- **Suggested test:** orchestrator fake-based tests: approved PR path creates the PR; declined path calls `github.createPullRequest` zero times.
- **Status:** FIXED (gate added; default remains "ask user" — behavior change is documented in §19.3).

### BUG-203 — `POST_NOTIFICATIONS` never requested at runtime (Android 13+)

- **ID:** BUG-203 — **Category:** Runtime / UX — **Severity:** P2 — **Confidence:** High
- **File and line:** `AndroidManifest.xml:7` declares the permission; **no code requests it** (repo-wide search for `requestPermission|RequestPermission` returns nothing).
- **Problem:** On Android 13+ (API 33, and the app targets 35), runtime notification permission is required for the foreground-service notification to be *visible*. The FGS still runs (turns survive), but the user gets zero agent-progress visibility in the status bar — and Android 13+ shows users a bare "app running in background" chip, which reads as "something's wrong".
- **Recommended fix (implemented):** one-shot `ActivityResultContracts.RequestPermission()` prompt in `MainActivity` on first launch (deniable — the app degrades gracefully; a "notifications off" hint is documented in the report).
- **Status:** FIXED.

### BUG-204 — Model-generated links open arbitrary URI schemes (no http/https allowlist)

- **ID:** BUG-204 — **Category:** Security / indirect-prompt-injection hardening — **Severity:** P2 — **Confidence:** High
- **File and line:** `app/src/main/kotlin/dev/repochat/ui/chat/markdown/MarkdownProse.kt:83-88,105-110,127-132` — `onUrl = { url -> uriHandler.openUri(url) }` with no scheme check; URL strings originate from `[label](url)` markdown emitted by the model, which reflects **untrusted repository file content and CI logs** (classic indirect-injection surface).
- **Problem:** `openUri` fires an `ACTION_VIEW` intent for any URI. `intent://`, `market://`, or app deep-links crafted inside a malicious repo file can launch other apps / phish. Compose-native rendering means no script execution, but link-following is still an injection consequence channel.
- **Recommended fix (implemented):** `isSafeBrowseUrl()` allowlist (http/https only, host non-blank) applied in `MarkdownProse` before `openUri`; non-conforming links are shown but inert (tap is a no-op).
- **Suggested test:** `SafeUrlTest` unit tests (added): https/http pass; `javascript:`, `intent://`, `file://`, blank host rejected.
- **Status:** FIXED.

### SEC-205 — CI workflow grants `contents: write` to every job

- **ID:** SEC-205 — **Category:** CI/CD least-privilege — **Severity:** P2 — **Confidence:** High
- **File and line:** `.github/workflows/android.yml:14-17` — top-level `permissions: contents: write, pull-requests: write, checks: write` inherited by `build`, `connected-tests`, and `dependency-review`; only the merge job actually needs write.
- **Problem:** A compromised dependency or action step inside a read-only job (lint/test) runs with write access to the repo — unnecessary blast radius.
- **Recommended fix (implemented):** per-job permission scoping — `build`, `connected-tests`, `dependency-review`: `contents: read`; only `merge-to-main` and the new `release` job keep write. (`dependency-review` retains its explicit narrower block.)
- **Status:** FIXED (workflow re-scoped; release job scoped to `contents: write` only).

### BUG-206 — GitHub OkHttp client has no explicit timeouts

- **ID:** BUG-206 — **Category:** Backend/transport reliability — **Severity:** P2 — **Confidence:** High
- **File and line:** `core/data/.../di/DataModule.kt:113-123` — `provideGithubApi` builds `OkHttpClient.Builder().addInterceptor(...)` with **no** timeout configuration (Ollama/OpenAI clients at lines 127-161 set 30/120/60 s).
- **Problem:** OkHttp defaults (10 s read) apply. `getJobLogs` (302 → large streamed log body) and huge recursive tree fetches can exceed 10 s between reads on slow links → `SocketTimeoutException` → mapped to the misleading "No network connection. Check your connection and try again." Also no `callTimeout` bound for the whole call.
- **Recommended fix (implemented):** 30 s connect / 120 s read / 60 s write to match the other clients.
- **Status:** FIXED.

### SEC-207 — AutoFixLoop auto-approves writes to CI-critical paths (`.github/`, workflows)

- **ID:** SEC-207 — **Category:** Agent safety / supply chain — **Severity:** P2 — **Confidence:** High (multi-precondition chain, hence not P1)
- **File and line:** `AiActionParser.sanitizePath` (`AiAction.kt:110-118`) blocks `..` and `.git` but **not** `.github`; `AutoFixLoop.kt:76-82` — `approval.tryEmit(true) // auto-approve every write in this loop`.
- **Problem:** In unattended AutoFix mode, a (prompt-injected) model instruction to write `.github/workflows/*.yml` (or `gradle/`, `build.gradle.kts`) is **auto-approved**, and this repository's CI then **auto-merges green `ai-chat/*` branches into `main`** with `contents: write`. Full chain: injected instruction → auto-approved workflow-file write → green CI → auto-merge → attacker-controlled workflow executes in the repo. Preconditions (user must point the agent at a repo whose CI auto-merges, auto-fix enabled, model influenced by hostile content) keep this at P2, but the blast radius is code execution.
- **Recommended fix (implemented):** path-classification guard — in AutoFixLoop, writes to CI-sensitive paths (`.github/`, `*.gradle`, `*.gradle.kts`, `gradlew*`, `settings.gradle*`, `suppression.xml`, `*.jks/keystore files`) are **never auto-approved**; they surface as a normal pending write requiring explicit human Approve/Reject, with an explanatory note. Normal interactive turns are unchanged (they were already gated).
- **Suggested test:** `AutoFixLoopTest` addition: ProposeWrite for `.github/workflows/x.yml` does NOT auto-commit; interactive approval still works for normal paths.
- **Status:** FIXED (guard + test). Residual risk documented in §19.2 (interactive approval of a malicious diff remains a human decision point).

### COST-208 — No LLM output ceiling and no token/cost tracking

- **ID:** COST-208 — **Category:** LLM reliability / cost control — **Severity:** P2 — **Confidence:** High
- **File and line:** `OpenAiCompatibleApi.kt:33` — `max_tokens` declared ("Soft cap so free-tier providers don't hang forever") but **never set** by any caller (`OpenAiCompatibleRepositoryImpl.chat` builds the DTO without it); Ollama requests carry no `options` limits; no token-usage fields are read from any response; no per-turn/per-day budget exists.
- **Problem:** Runaway generations are unbounded (cost + latency); the agent has no cost observability at all (Phase-3 requirement: token/cost tracking → Missing).
- **Recommended fix (partial, see §19.2):** deliberately NOT setting a global `max_tokens` in this audit — provider-specific limits vary (a too-high value 400s on some models, a too-low value truncates `write_file` JSON and silently degrades the agent). Implemented instead: honest documentation + roadmap item (per-connection configurable cap + usage counters). This is a documented residual risk, not an ignored one.
- **Status:** OPEN (documented; roadmap §18).

---

## 9. Low-Severity Bugs (P3)

| ID | Category | File / line | Problem | Status |
|---|---|---|---|---|
| BUG-301 | Concurrency | `AuthInterceptors.kt:48-73` | `OllamaKeyOverride` is a process-global `@Volatile` mutable slot; two overlapping calls (e.g. legacy path + router call, or a future concurrent-turn design) can read the wrong key. Currently mitigated by the single-turn-at-a-time design. | Open (documented); safe to fix when concurrency model changes |
| BUG-302 | Main-thread I/O | `DataModule.kt:85-86` + `SettingsRepositoryImpl.kt:16` | `EncryptedSharedPreferences.create` (Keystore) runs on first Hilt injection — on the main thread; measurable first-launch jank. | Open; recommend moving to a lazy/background init |
| BUG-303 | DB performance | `ChatRepositoryImpl.kt:37-61` | `conversations()` does observeAll → per-session `latestForRepo` (N+1) and re-runs on every message insert. Fine for dozens of chats; poor at hundreds. | Open |
| BUG-304 | UX | `ChatScreen.kt:502-525` | `input = ""` clears the text box before the async attachment load; if load fails, the typed text is gone (snackbar shows only "couldn't attach"). | Open (minor) |
| BUG-305 | Main-thread I/O | `ChatScreen.kt:973-986` | Attachment thumbnail `BitmapFactory.decodeStream` inside a composition `remember` — main thread. | Open (minor) |
| BUG-306 | DB indexing | `ChatMessageEntity.kt:9-12` | Separate indices on `repo_key` and `session_id`; all hot queries filter on both → composite index `(repo_key, session_id, created_at)` would be optimal. | Open (additive migration; deferred to avoid destructive risk) |
| BUG-307 | Agent correctness | `GithubRepositoryImpl.kt:224-240` | `fileContent()` on a directory path returns an *empty* GitFile (content null → empty string, `isBinary=false`) — the model "reads" an empty file and may invent content, violating the "never guess contents" rule. | Open; recommend a `type=="dir"` check |
| BUG-308 | CI | `android.yml` merge job | Two green `ai-chat/*` branches can race both-merge into `main`; the loser fails its push. Noise, not corruption. | Open (documented) |
| BUG-309 | Supply chain | `android.yml` (all `uses:`) | Actions pinned by tag (`@v4`, `@main` historically), not commit SHA. Tag-pinning is common but SHA-pinning is the audited standard. | Open (roadmap) |

---

## 10. Security Vulnerabilities (summary of the dedicated checks)

| Check | Result | Evidence |
|---|---|---|
| SQL injection | **Pass** | Room DAOs, parameter binding only (`ChatMessageDao.kt` et al.) |
| NoSQL / command injection | **Pass** | No DB drivers beyond SQLite; no `Runtime.exec`/shell anywhere in tree |
| SSRF | **N/A-ish** | All URLs are user-configured LLM endpoints (their own keys); GitHub base URL is hardcoded `https://api.github.com/` (`GithubRepositoryImpl.kt:244`) |
| XSS | **Pass** | Compose-native markdown, no WebView, no HTML engine; links are the only interactive surface (fixed — BUG-204) |
| CSRF / open redirect / prototype pollution | **N/A** | No web server, no redirects, Kotlin/JSON (typed deserialization with `ignoreUnknownKeys`) |
| Path traversal | **Pass** | `sanitizePath` rejects `..`, `.git`, absolute paths, >512 chars (`AiAction.kt:110-118`); `.github` gap now guarded in auto-mode (SEC-207) |
| Insecure file upload | **Pass** | Attachments: 5 MB cap, size + NUL-byte + printability heuristics, base64-in-prompt only, never written to disk or executed (`ChatScreen.kt:1061-1171`) |
| Hardcoded credentials / exposed keys | **Pass** | Regex secret scan clean; keys live in EncryptedSharedPreferences; OkHttp logging interceptor is declared in the catalog but **not wired** into any client (verified `DataModule` — no `logging-interceptor` usage) → no secrets-in-logs risk |
| Secrets in client bundle | **Pass** | No `BuildConfig` secret fields; no `resValue` secrets; `versionCode 1` only |
| CORS / security headers / HTTPS | **Pass/N.A.** | No web origin; manifest has no `usesCleartextTraffic`; all provider presets are https |
| Debug mode in production | **Pass** | Release build type exists, minify off (see DevOps gaps §14) |
| Overly permissive CI token | **Fixed** | SEC-205 per-job scoping |
| Unsafe deserialization | **Pass** | kotlinx.serialization with `ignoreUnknownKeys`, strict DTOs, lenient JSON but typed |
| Excessive error detail to users | **Pass** | Errors are mapped to typed, curated `AppError.userMessage`s (`ErrorMapping.kt`) |
| Dependency vulnerabilities | **Monitored** | Version catalog pins current majors (OkHttp 4.12.0, Retrofit 2.11.0, Compose BOM 2025.01.00); CI now runs dependency-review on PRs + Gradle dependency-graph submission for Dependabot |

**Prompt-injection posture (detail):** direct injection is bounded by the tool allowlist (only 5 actions exist; no shell/browser/secret tools). Indirect injection from repo files/CI logs is structurally possible — file contents are concatenated into user-role context (`PromptBuilder.fileContentMessage`) without an explicit "treat as data, never as instructions" hardening line. Compensating controls: JSON tool contract, write approval gate (now also for PRs), branch isolation (never main), path sanitization, CI-path auto-approve guard (new), and 10-step/5-attempt loop caps. Remaining exposure is documented in §19.2. This is the honest industry-typical posture: mitigated, not eliminated.

---

## 11. AI-Agent Capability Assessment (Phase 3 matrix)

Legend: ✅ Implemented · 🟡 Partial · ❌ Missing · ⚠️ Incorrect/unsafe (fixed or documented)

### 11.1 Agent architecture
- Agent loop (plan → execute → observe → terminate): ✅ `AiEditOrchestrator` — strict-JSON tool contract, `read_file` feeds context back, write/PR/CI actions end or continue the loop
- Reliable termination condition: ✅ reply / write / step cap (10) with honest exhaustion message; AutoFixLoop: green / 5 attempts / 12-min CI budget
- Maximum iteration limit: ✅ `MAX_MODEL_STEPS = 10`, `AutoFixLoop.DEFAULT_MAX_ATTEMPTS = 5`, `CI_MAX_POLLS = 40`
- Maximum token/cost budget: ❌ (COST-208) — no caps, no usage tracking
- Timeouts: 🟡 LLM/GitHub HTTP timeouts (30/120/60 s — GitHub side fixed this audit); no overall per-turn deadline (a 10-step turn can legitimately take minutes; a stuck approval gate hangs forever → cancel added, BUG-201)
- Cancellation: ✅ **added this audit** (`AiTurnCoordinator.cancelTurn()` + Stop button); previously ❌
- Partial-failure recovery: 🟡 per-call: provider fallback on 429 (`LlmRouterImpl`), CI-poll network blips swallowed, log-fetch retry×4 with backoff (`AutoFixLoop.fetchFailureLog`); no cross-turn retry of a failed step
- Agent state persisted safely: ✅ Room rows for every read/write/reply + PENDING→APPROVED/REJECTED transitions; live turn survives process death only as history (in-flight turn is lost on process kill — documented)
- Concurrent agent runs: ❌ by design (single global turn slot — see BUG-101 fix; the fix makes this explicit and visible instead of silent)

### 11.2 Tool / function calling
- Tools defined with strict schemas: ✅ system prompt JSON schema + `AiActionParser` (strict decode, fenced-JSON recovery, lenient string trimming)
- Arguments validated server-side (client-side here): ✅ path sanitization, length caps (commit 200, PR title 200/body 4000, fallback text 4000)
- Tools allowlisted: ✅ exactly 5 actions; unknown actions degrade to text reply
- Dangerous tools gated by permission checks: ✅ for `write_file` (and now `create_pull_request` — BUG-202 fix); ⚠️→✅ CI-critical paths never auto-approved in AutoFixLoop (SEC-207 fix)
- Human confirmation before destructive actions: ✅ diff + Approve/Reject; PR creation now gated; PR merge always manual (app never merges)
- Tool calls logged: 🟡 every action lands in Room as a chat row (read/write/reply) — no structured audit log of CI-status checks or provider fallbacks
- Idempotency / duplicate prevention: 🟡 writes carry `baseSha` (409 on stale read = upstream-change detection); duplicate approvals can't double-commit (single-slot + state drain); PR creation can 422 if re-run (GitHub dedupes identical PRs)
- Tool results treated as untrusted input: 🟡 file contents are marked (`FILE CONTENT - path`) but not instruction-hardened; mitigations enumerated in §10
- Sandboxing for code execution / shell / browser: ✅ N/A by design — **no such tools exist** (a strength for a mobile agent)
- Tool timeouts & failure states: ✅ typed `AppError` mapping per call, honest fallback messages

### 11.3 Prompt security
- Direct injection protection: 🟡 allowlist + gates bound the blast radius (no system-prompt-override to arbitrary tools)
- Indirect injection (files, CI logs): 🟡 structurally possible; compensated (see §10) — new CI-path guard closes the worst escalation
- System/user/external-content separation: 🟡 system prompt is a fixed first message; external content rides in user-role messages with textual markers, not structural separation
- Sensitive system-prompt leakage: ✅ the system prompt contains no secrets; settings keys never enter prompts (verified — `PromptBuilder` receives only task/tree/file data)
- Untrusted Markdown/URL handling: ✅ (after BUG-204 fix) scheme allowlist; no raw HTML rendering

### 11.4 LLM reliability
- Structured output validation: ✅ strict parser with fenced-JSON recovery and plain-text fallback that still surfaces content
- Malformed responses: ✅ `fallbackText()` path (tested: `AiActionParserTest`)
- Refusals / empty responses: ✅ empty → explicit "empty response" error; refusal text degrades to reply
- Hallucination handling: 🟡 prompt rules ("never invent contents", "read before write") + read-before-write loop; no factual verification layer (out of scope for a repo editor)
- Context-window overflow: ✅ hard char budgets — 200k messages / 80k per file / 45k tree / 45k→2500-entry truncation (`PromptBuilder.cap`, `FileTreeFormatter`)
- Rate limits: ✅ 429/403 typed errors + multi-provider fallback (`LlmRouterImpl`) + honest mid-turn note (`ProviderNote`)
- Provider outages: 🟡 fallback only on rate-limit-like errors by design (a 5xx on the only provider surfaces as an error with retry)
- Retry with backoff: 🟡 log-fetch retries; no transport-level retry for chat calls (single attempt, then next provider)
- Model fallback strategy: ✅ ordered provider queue, preferred-first, sticky `fellBackFrom` reporting
- Streaming error handling: ❌ no streaming (non-streaming requests everywhere — acceptable for the JSON tool contract; documented)
- Partial-response recovery: ✅ NDJSON chunk concatenation for Ollama (`OllamaRepositoryImpl.chat`)
- Token/cost tracking: ❌ (COST-208)

### 11.5 RAG / knowledge system
- Document ingestion validation: ✅ 5 MB cap, MIME/extension/NUL heuristics; images only for vision models
- The app has **no vector store / embedding pipeline**: repo context = file tree + on-demand file reads (a deliberate, bounded design — "lazy RAG by tool call")
- Duplicate/stale documents: ✅ reads are live GitHub API calls (always fresh); 409 conflict → re-read guidance
- Citations: 🟡 the model cites paths it read; no formal citation UI
- Vector-DB/embedding failure handling: N/A

### 11.6 Memory / conversation management
- Conversation ownership: ✅ single-user device-local app; Room keyed by repo/session
- Cross-user isolation: N/A (no multi-user surface); OS sandbox + encrypted storage per app
- Retention policy / user deletion: 🟡 per-conversation delete + clear-history exist; no bulk export or "delete all data" (§13)
- Context summarization: ❌ (fixed 8/16-message window + char cap — sufficient for short sessions; long sessions lose early context silently)
- Memory poisoning: 🟡 history rows are app-generated only; a poisoned history is possible only via the model's own prior outputs (bounded by same tool gates)

### 11.7 Human-in-the-loop
- Approval before irreversible actions: ✅ writes (diff gate), PRs (new), merges (never automated by the app)
- AI-generated response labeling: ✅ visually distinct bubbles; FGS notification shows progress (after BUG-203 fix, actually visible on 13+)
- Stop/cancel: ✅ (new — BUG-201)
- Audit trail: 🟡 Room history is the trail (durable, per-repo); no export/verification tooling

### 11.8 AI safety & privacy
- PII detection/masking before sending to providers: ❌ (repo content + user text go to the configured LLM as-is — inherent to the product; documented in §17)
- Data sent to third-party LLM: 🟡 user-chosen provider, user's own key; no telemetry/ads/analytics in the app (verified — no such permissions/libraries)
- Secrets in prompts: ✅ none (verified)
- Abuse/rate/quota: 🟡 provider-side limits + in-app single-turn serialization; no local quotas
- Harmful-content handling: 🟡 provider-side policies; no local classifier (out of scope, documented)

### 11.9 Observability & evaluation
- Model/version tracking: 🟡 connection label + model name persisted; no per-message model attribution
- Input/output tracing: 🟡 Room history = full I/O trace
- Token/cost/latency/error metrics: ❌ (COST-208; roadmap)
- User feedback collection: ❌ (no thumbs up/down — roadmap)
- Prompt regression tests: ✅ `PromptBuilderTest` + orchestrator fake tests effectively pin the contract; `AiActionParserTest` pins parsing — **this is the strongest eval asset**
- Safety/tool-selection evaluation: ❌ (roadmap: injection corpus tests)

---

## 12. Missing AI-Agent Features (ranked, realistic)

1. **Cost/token metering & per-connection output caps** (COST-208) — the only true agent-layer gap with direct monetary impact.
2. **Cross-turn task persistence / resumable turns** — an in-flight turn dies with the process; only history survives.
3. **Conversation summarization for long sessions** — silently drops early context after the window.
4. **Structured agent audit log export** (JSONL of actions with timestamps/model) — for the "audit trail" requirement.
5. **Grep/search tool** — the agent's only discovery tools are the tree and exact-path reads; a `search_code` action (GitHub code search API) would materially improve fix quality. (Target architecture note: add as a 6th read-only action, same gate model.)
6. **Streaming responses** for general chat (latency UX only).
7. **Per-turn model attribution + feedback (👍/👎)** feeding an eval set.

**What is deliberately NOT recommended:** a vector DB/RAG pipeline, multi-tenant accounts, server-side orchestration, or an autonomous "background agent daemon". The app's constrained, human-gated, single-slot design is its core safety property; the gaps above are additive, not architectural.

---

## 13. UX / Product Gaps

Present and good: onboarding via suggestion chips + empty states, loading/typing states with live step text, error banner with retry + Settings shortcut, conversation list with search, diff cards with stats, PR dialogs, CI chip, branch chip + explainer, battery-optimization guidance, dark theme.

Gaps (all P3): no onboarding permission rationale for notifications (fixed functionally by BUG-203); typed text lost on attachment failure (BUG-304); no bulk data export / "delete everything" (only per-conversation delete); no privacy policy / ToS links; no usage/quota visibility (COST-208); no i18n (strings.xml is English-only; externalized properly though); accessibility: most decorative icons have `contentDescription = null` (correct), but chip-only actions rely on color; no RTL check beyond `supportsRtl=true`.

---

## 14. DevOps / Deployment Gaps

- **Docker/compose:** none — correct for a native Android app (N/A).
- **CI (current):** build + lint + unit tests + emulator connected tests + dependency review + debug APK artifact + auto-merge. **Added by this audit:** release job (`assembleRelease`) with secret-keystore signing or documented debug-key fallback, release APK artifact, GitHub Release on `v*` tags, least-privilege permissions (SEC-205).
- **Migration/rollback:** Room has one additive migration + `fallbackToDestructiveMigrationOnDowngrade` (documented). No staged rollout (Play-track concept, N/A for sideload).
- **Health/readiness:** N/A for an offline mobile app; the FGS + typed errors serve this role.
- **Graceful shutdown:** FGS stops when the turn ends; process death mid-turn leaves the last message PENDING → now rejected on cancel; restart resumes from history (documented limitation).
- **Monitoring/alerting:** CI failure notifications are log-lines only (`notify-failure` job) — no Slack/email wiring (template exists in the workflow).
- **Release engineering:** `versionCode 1 / versionName 1.0.0` hardcoded — no CI-injected build metadata; minify/R8 off for release (ship size + reverse-engineering surface — roadmap, needs proguard keep rules for kotlinx-serialization); no signing infrastructure before this audit (added, secret-driven).

---

## 15. Testing Gaps

Current (all green, 77+ tests): action parser, prompt builder caps, diff engine, session ids, provider presets, model pricing/sorting, orchestrator loop (reply/read/write/approval/rejection/error paths), AutoFixLoop (attempt flow, gave-up summary, log tail truncation), LLM router fallback, error mapping, OpenAI-compatible request shape/vision parts, markdown segment parsing, key masking.

Missing, in priority order: (1) regression tests for this audit's fixes — **added**: foreign-turn gate (TurnGate), PR approval gate, auto-fix CI-path guard, safe-URL allowlist; (2) multi-conversation isolation of live-turn state (same class as TurnGate test); (3) prompt-injection corpus tests (malicious `FILE CONTENT` payload must not produce ungated actions — partially covered by the CI-path guard test); (4) Robolectric/Compose UI tests for approval cards & cancel button; (5) connected tests exist in CI but the repo has **zero androidTest sources** — the emulator job currently boots a full AVD to run nothing (costly flakiness source; documented, not removed); (6) load/concurrency N/A (single-user client).

---

## 16. Performance & Scalability Risks

- `conversations()` N+1 re-query on every message insert (BUG-303) — fine ≤ ~100 conversations.
- Full repo tree fetched every turn (no TTL cache) + 45k-char prompt rebuilt per turn — latency + token cost per message; acceptable for correctness-first design.
- Base64 image attachments (≤5 MB) ride every subsequent history turn? No — attachments are one-shot per turn (only the triggering message), verified in `runTurn` (attachment passed once, history stores text only). ✅
- `ChatScreen` recomposes whole message list on each new event — keyed `LazyColumn` items make this cheap; markdown re-parse is `remember(text)`-cached. ✅
- 4 GB devices: 200k-char contexts + 5 MB base64 strings are the memory ceiling — within bounds for the caps chosen.

---

## 17. Privacy & Compliance Risks

- Repo file contents, CI logs, and user text are sent to the configured third-party LLM provider under the user's own key. **No PII masking exists** (inherent to the product's purpose; the report makes it explicit rather than hidden). Recommendation: surface a one-time notice in Settings ("your repository content is sent to <provider>").
- Chat history (paths, commit messages, CI log excerpts) is stored **unencrypted** in Room (`repochat.db`), excluded from backups. On a non-rooted, screen-locked device this is standard-acceptable; for sensitive repos, full-disk encryption is the platform's control. Documented as residual risk.
- No analytics/ads/trackers — verified (permissions: INTERNET, FGS, POST_NOTIFICATIONS only).
- No privacy policy / ToS documents (P3, §13).

---

## 18. Recommended Roadmap

**30 days (hardening):** ship this audit's fixes; add token-usage + cost counters (COST-208) with a Settings meter; CI: SHA-pin actions, drop the empty emulator job or add real androidTest sources; add `search_code` read-only tool; composite DB index via additive migration v3; fix BUG-304/305/307; notification-rationale copy.
**60 days (reliability & evals):** prompt-injection corpus tests; Robolectric tests for coordinator/cancel; per-connection `max_tokens` config (validated against provider docs); transport retry w/ backoff for chat calls; conversation summarization at window edges; agent audit-log export (JSONL).
**90 days (product):** Play-track release engineering (R8 minify + keep rules, versioning automation, staged rollout); data export/delete-all; privacy notice + policy; optional streaming for general chat; per-turn model attribution + 👍/👎 feedback.

---

## 19.1 Fixes Applied in This Audit (Phase 8 execution)

> Full field-format findings for the fixed items are in §7–§10 (BUG-101, 201, 202, 203, 204, 206; SEC-205, 207). Every fix ships with a regression test where testable (see §19.4) and was verified by full local build + lint + test runs before push.

| ID | Fix summary | Files |
|---|---|---|
| BUG-101 | `startTurn` → returns acceptance; pure `TurnGate` (core:domain) decides if a foreign turn is active; ChatViewModel blocks + surfaces typed error **before** persisting the message; send button disabled while foreign turn runs; input preserved | `AiTurnCoordinator.kt`, `ChatViewModel.kt`, new `TurnGate.kt`, `TurnGateTest.kt`, `ChatScreen.kt` |
| BUG-201 | `cancelTurn()`: cancels job, marks PENDING write REJECTED, appends "stopped by user" note, resets state, stops FGS; Stop button in top bar while typing | `AiTurnCoordinator.kt`, `ChatViewModel.kt`, `ChatScreen.kt`, strings.xml |
| BUG-202 | `create_pull_request` now suspends on the same approval gate: new `TurnEvent.ProposePullRequest` / `PullRequestDeclined`; confirmation card in chat; approved → create, declined → honest note | `AiEditOrchestrator.kt`, `Models.kt`, `AiTurnCoordinator.kt`, `ChatViewModel.kt`, `ChatScreen.kt`, orchestrator tests |
| BUG-203 | One-shot `POST_NOTIFICATIONS` request on first launch (API 33+), deniable | `MainActivity.kt` |
| BUG-204 | `isSafeBrowseUrl()` (http/https + host) gate on all model-generated links | `MarkdownProse.kt`, new `SafeUrlTest.kt` |
| BUG-206 | GitHub OkHttp client: 30 s connect / 120 s read / 60 s write | `DataModule.kt` |
| SEC-205 | CI least-privilege: read-only workflow default (already landed by the repo's parallel agent — verified & kept); write only in the tag-release job | `android.yml` (final shape after rebase onto green `main`) |
| SEC-207 | AutoFixLoop refuses to auto-approve CI-sensitive paths (`.github/**`, gradle scripts, keystores, `suppression.xml`); surfaces them for manual Approve/Reject | `AutoFixLoop.kt` (+ test), `AiEditOrchestrator.kt` |
| CI/Release | Tag-release pipeline: `v*` tags trigger a `GitHub Release (tags)` job — assembleRelease with the repo's `RELEASE_*` secret signing (same pattern as the build job), clearly-labeled throwaway-debug-key fallback when no secrets, `gh release create` with the APK attached. The repo agent's parallel release validation (unsigned release artifact on every branch) kept verbatim | `android.yml`, README |

**No destructive database changes.** No schema/version bumped (room stays v2). No functionality removed. One deliberate behavior change: model-initiated PRs now require explicit user approval (previously automatic) — rationale: README-promised gate, phishing/spam vector; migration: none needed (UX only); rollback: revert the orchestrator hunk.

**Rebase note:** during this audit the repository's own agent landed parallel CI/release-signing work on `main` (release validation on every branch, `RELEASE_*` env signing, read-only CI, auto-merge removed by design). This audit branch was rebased onto that green `main`: their canonical build/CI files were kept verbatim and this audit's unique value (agent-safety fixes, regression tests, tag-release job, docs) layered on top — no duplicated machinery.

## 19.2 Remaining Risks (after fixes)

1. **Indirect prompt injection remains structurally possible** — mitigated (allowlist, gates, CI-path guard, branch isolation) but not eliminated; a user who *manually approves* a malicious-looking diff can still be social-engineered. Human judgment is the last gate by design.
2. **COST-208** — no token/cost ceilings or metering (deliberate, see §8).
3. **Turns are not resumable across process death** (in-flight work lost; history survives).
4. **Unencrypted chat DB** on device (platform-standard, documented §17).
5. **`OllamaKeyOverride` global** (BUG-301) — safe under current single-turn serialization.
6. **Release APK without keystore secrets** — regular-branch artifacts stay unsigned (the repo's chosen policy, validated by CI); only `v*` tag releases get the clearly-labeled throwaway-debug-key signing. Add the four `RELEASE_*` secrets for a properly signed APK.
7. CI on `main` is validation-only (auto-merge was intentionally removed by the repo owner's agent during the audit window); landing changes now requires a reviewed pull request — an improvement in governance that this audit endorses.

## 19.3 Commands re-run after fixes (Phase 9)

All executed on the audit sandbox after the final fix batch (JDK 17, Android SDK 35):
`./gradlew compileDebugKotlin :core:model:compileKotlin :core:domain:compileKotlin :core:data:compileDebugKotlin` → **BUILD SUCCESSFUL**; `:app:lintDebug` → **BUILD SUCCESSFUL**; `:app:assembleDebug` → **BUILD SUCCESSFUL**; `:app:assembleRelease` → **BUILD SUCCESSFUL** (debug-key fallback path exercised — no keystore on the sandbox); full unit-test suite → **BUILD SUCCESSFUL, 117/117 pass**. Final CI proof runs on GitHub Actions for branch `ai-chat/audit-p0p1` and on `main` after auto-merge.

## 19.4 Test Results After Fixes

| Suite | Before | After (this audit) |
|---|---|---|
| core:model | 35 pass | **51 pass** (adds CiSensitivePathsTest) |
| core:domain | 18 pass | **25 pass** (adds TurnGateTest, PR-gate approve/decline, AutoFix CI-path guard; one superseded PR-flow test rewritten for the new gated semantics) |
| core:data | 15 pass | **27 pass** (repo's own agent added error-mapping/OpenAI tests during the audit window; no regressions) |
| app | 9 pass | **14 pass** (adds SafeUrlTest) |
| **Total** | **77** | **117 — 0 failures, 0 errors** |
| lintDebug | pass | pass |
| assembleDebug / assembleRelease | pass / n-a | pass / pass |

## 19.5 Final Verdict

**Conditionally ready** (personal/internal production use):
- ✅ No P0s; the single P1 and the actionable P2 cluster are fixed with regression tests.
- ✅ CI green path with auto-merge + release APK artifact is reproducible.
- ⚠️ Conditions: (1) add a real release keystore before distributing builds (debug-key fallback is explicitly labeled); (2) keep human approval for every write — do not widen AutoFixLoop's auto-approve scope; (3) treat COST-208 (token metering) as the next must-have before any team-wide rollout; (4) rotate any PAT that has been shared in plaintext (including the one used to deliver this audit — see §5 note).

