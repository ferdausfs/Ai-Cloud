# RepoChat AI

A native Android chat app where you talk to an AI pair programmer that reads
and edits files in a GitHub repository you select — with **every commit
landing on a safe working branch, never on `main`**, and a diff +
Approve/Reject gate before anything is written.

## Supported AI providers

| Provider | API | Model discovery | Notes |
|----------|-----|-----------------|-------|
| **Ollama Cloud** | bespoke `/api` (NDJSON) | live `GET /api/tags` | Original backend |
| **OpenRouter** | OpenAI-compatible | live `GET /v1/models` | `:free` routes flagged FREE, other routes PAID |
| **Groq / Cerebras / Together / Fireworks / Custom** | OpenAI-compatible | live `GET /v1/models` | Any OpenAI-shaped endpoint works via *Custom* |
| **Experiential Labs** | OpenAI-compatible | live `GET /v1/models` | First-class preset; see below |

The chat/coding agent is provider-agnostic: switching provider or model only
changes the intelligence backend. Read files, diffs, approvals, working
branches, PRs and CI auto-fix behave identically everywhere.

## Experiential Labs setup

1. Create an API key on the [Experiential Labs platform](https://platform.experientiallabs.ai)
   (keys look like `xpl_…`).
2. In the app: **Settings → AI Providers → + OpenAI-compatible**, choose the
   **Experiential Labs** preset (endpoint `https://api.experientiallabs.ai/v1`
   is locked to the official URL), paste the key, and **Load models**.
3. **Test connection** performs a real authenticated `GET /v1/models` — it
   validates your key without spending tokens on a chat completion.

The model list is fetched live from the provider catalog. A small curated
fallback list is used only when live listing is unavailable; the provider's
catalog can change at any time and the app never assumes specific model ids
keep existing.

### Free, promotional and paid models

The model picker labels every model honestly:

- **FREE** — verified zero-cost route (OpenRouter `:free` ids).
- **PROMO** — a promotional free tier the provider grants for a limited time
  (currently e.g. `gpt-6-astra`, `claude-fable-5.1` on Experiential Labs).
  **Free/promotional availability is determined by the provider and may
  change.** The app never claims a model is permanently free.
- **PAID** — shown only when the provider's own catalog semantics prove it
  (OpenRouter non-`:free` routes bill at provider price).
- **N/A** — pricing unavailable; the app does not invent pricing.

Quota errors are explained, not swallowed: when a free-tier daily/hourly cap
is reached you get the provider's own detail (e.g. `free_limit_reached`) plus
any `Retry-After` hint; `402 verification_required` surfaces as "payment
verification required on the provider's dashboard".

## Architecture

MVVM + Clean Architecture + Hilt, in four Gradle modules:

```
app/          Compose UI (screens, navigation, theme) + ViewModels
core/model/   Pure Kotlin domain model, JSON action parser, diff engine,
              prompt builder, provider presets, pricing classifier — tested
core/domain/  Use cases, repository contracts, the AI editing orchestrator
              (tool loop + approval gate), AutoFixLoop — tested with fakes
core/data/    Room, EncryptedSharedPreferences, Retrofit/OkHttp, GitHub and
              LLM API clients, Hilt DI module
```

Provider integrations share one OpenAI-compatible client; provider-specific
differences (request parameters, static headers, error semantics) live in the
`ProviderPreset` table in `core/model` — adding OpenAI/Anthropic/Gemini or any
OpenAI-shaped endpoint is a preset, not a rewrite. UI → ViewModel → UseCase →
`LlmService` → provider implementation → API.

## Highlights

- **AI tool-calling loop** — the model answers with strict JSON
  (`read_file` / `write_file` / `create_pull_request` / `check_ci_status` /
  `reply`). Requested files are fed back into context until it writes or
  replies.
- **Human-in-the-loop commits** — every proposed change is shown as a
  color-coded line diff with **Approve & commit / Reject** before the GitHub
  API is called.
- **Never pushes to main** — each repository gets a dedicated working branch
  (`ai-chat/<session-id>`), created from the default branch HEAD, visible as a
  chip in the chat screen. A **Create pull request** action opens a PR into the
  default branch; merging stays a manual step on GitHub.
- **Premium model picker** — live catalog with search, All/Free/Paid filters,
  FREE/PROMO/PAID badges, provider grouping, retry, and an offline fallback:
  the last successful catalog is cached and shown with an explicit
  "Offline — showing previously loaded models" indicator when the network
  fails.
- **Connection testing that tests** — every provider's Test Connection makes a
  real API request and maps 200/401/403/429/5xx/network-failure to precise,
  human-readable messages.
- **Secure credential storage** — API keys / GitHub PAT live in
  `EncryptedSharedPreferences` (Android Keystore-backed AES-256), excluded
  from backups. Keys are never logged, never embedded in build outputs, and
  provider cards show only a masked fingerprint (`xpl_••••••••9F3A`).
- **Robust error handling** — typed errors (401 → Settings, 402 → payment
  verification guidance, 403/429 → rate-limit message with `Retry-After`
  hint, 409 → re-read guidance, network → retry), truncated file trees for
  large repos, size-capped LLM context.

## Getting started

1. Open the project in Android Studio (Koala or newer).
2. Get at least one AI provider credential (an
   [Ollama Cloud](https://ollama.com) key, an
   [OpenRouter](https://openrouter.ai) key, an
   [Experiential Labs](https://platform.experientiallabs.ai) key, or any
   OpenAI-compatible endpoint) and a
   [GitHub personal access token](https://github.com/settings/tokens) with the
   **repo** scope.
3. Run the app, open **Settings**, add each provider, paste keys, pick models,
   and use **Test connection** per provider.
4. **Browse repositories**, pick one, and chat. Approve the diff when the AI
   proposes a change; create a PR when you're done.

## How a turn works

1. User sends a message → session is ensured in Room.
2. The working branch `ai-chat/<session-id>` is created from the default
   branch HEAD if it doesn't exist; concurrent creation races resolve
   gracefully (422 "already exists" is treated as success).
3. The recursive file tree is fetched and formatted into a size-capped prompt
   together with recent chat history.
4. The model responds with strict JSON; `read_file` actions pull file contents
   into context and loop (max 10 steps).
5. `write_file` renders a line diff; on **Approve** the app commits to the
   working branch via `PUT /contents/{path}` **with the branch explicitly
   passed** (never omitted). Rejected proposals never touch the repo.
6. **Create pull request** raises a PR from the working branch into the
   default branch — merging is always a separate manual step.

## GitHub Actions CI & auto-merge

`.github/workflows/android.yml` on every push:

1. **Build** — `:app:lintDebug`, `:app:assembleDebug`, the full unit test
   suite (`:core:model:test`, `:core:domain:test`, `:core:data:testDebugUnitTest`,
   `:app:testDebugUnitTest`), plus connected emulator tests. The build also
   submits the Gradle dependency graph, so known CVEs surface as Dependabot
   alerts; pull requests additionally run a Dependency Review gate.
2. **Auto-merge into `main`** — only `ai-chat/*` working branches are merged,
   and only after build + connected tests are green (`--no-ff` merge commit).
   `main` can never receive a red commit.

The app itself still never writes to `main` — AI commits always land on
`ai-chat/<session-id>` working branches.

## Release APK & signing

Every working-branch CI run validates **and uploads** both a debug APK and a
release APK (`app-release-apk` artifact). Without signing secrets the release
APK is **unsigned** — sign it before installing, or configure signing:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | base64 of your keystore file (`base64 -w0 release.jks`) |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

With all four set, CI signs the release APK on every non-PR build.

**Tag releases:** pushing a `v*` tag (e.g. `v1.0.1`) runs the
`GitHub Release (tags)` job — it builds the release APK and publishes a
GitHub Release with it attached. Without the secrets above it is signed with
a throwaway debug key and clearly suffixed `-debugsigned` (fine for
sideloading; **not** for Play distribution). Generate a real keystore with:

```
keytool -genkeypair -v -keystore release.jks -alias repochat \
  -keyalg RSA -keysize 2048 -validity 10000
```

## Production-readiness audit

`AUDIT_REPORT.md` contains the full evidence-based audit (architecture map,
bug/security/agent-safety findings with severity + regression tests, DevOps
and testing gaps, roadmap). Headline fixes already merged from it: the
foreign-turn send guard, turn cancellation, the pull-request approval gate,
the AutoFixLoop CI-path guard, notification permission request, safe-URL
allowlist for model links, and the least-privilege CI permissions + release
pipeline described above.

## Branch safety notes

- The app **never** omits the `branch` field on content writes, so commits can
  never silently default to `main`.
- File paths from the model are sanitized (no `..`, no `.git`, no traversal).
- If a file changed upstream, GitHub returns 409 and the app asks you to simply
  resend the message so the file is re-read first.

## Background AI turns

In-flight turns run inside `AiTurnCoordinator` (application scope) behind an
`AiTurnService` foreground service (`dataSync`) so leaving the app (home /
screen off) does not cancel the network call. Results are still persisted in
Room — reopen chat to see the completed reply/diff/PR.

Even with a foreground service, some OEMs (especially Xiaomi/MIUI, Vivo, Oppo)
add a manual battery-optimization / Autostart whitelist. Settings has a one-line
tip and a button that opens this app's system details page so you can set
battery to "No restrictions".

## Auto-fix until CI is green

Chat has an opt-in checkbox **"Auto-fix until CI passes"**. When enabled, the
send path runs `AutoFixLoop` under the same foreground service:

1. One AI turn (read/write) — writes are auto-approved so the loop can run unattended
2. Poll GitHub Actions on the working branch (~15s exponential backoff, up to ~12 minutes)
3. On failure: fetch the failed job's real log (tail ~8k chars), re-prompt the model, commit again
4. Stop on green, or after the configured max attempts (default 5) with an honest summary

**Safety guard:** auto-fix never auto-commits changes to CI/build
configuration (`.github/**`, Gradle scripts, `gradlew`, keystores,
`suppression.xml`). Those are declined with an explanation so you can review
the diff in a normal, gated chat turn instead — an injected instruction
cannot turn the unattended loop into a CI-code execution path.

Never claims success it didn't achieve — CI is the source of truth. Progress
is written as chat bubbles and shown on the FGS notification
(`Attempt 2/5 — CI failed, fixing…`). You can always stop the loop with the
✕ button in the chat top bar.

## Troubleshooting

- **"Your … API key was rejected"** — the key is invalid/expired; update it in
  Settings and re-test.
- **"LLM provider rate limit reached. Detail: …"** — free-tier quota exhausted
  (e.g. daily/hourly cap). Wait for the reset, retry, or switch provider — the
  router auto-falls back to the next configured provider on rate limits.
- **"payment verification required"** — the provider gates this model behind
  card verification; switch to a free/promotional model or complete
  verification on the provider's dashboard.
- **"Could not load models"** — check connectivity; the picker shows your last
  successfully loaded catalog with an Offline indicator, and you can always
  enter a model id manually.
- **Turn stuck on "Approving…"** — should not happen anymore (fixed); if you
  ever see it, tap Approve/Reject again or restart the app — history is safe
  in Room.
- **Background turn dies** — whitelist the app from battery optimization
  (Settings → battery tip button).

## Development

```bash
./gradlew test          # all unit tests
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Unit tests cover the action parser, path sanitization, prompt builder, diff
engine, provider presets, pricing classification, request shaping (vision
parts, capability flags), error mapping (401/402/429/Retry-After), the
provider router fallback and the AutoFixLoop.
