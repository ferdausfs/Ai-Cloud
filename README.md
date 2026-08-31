# RepoChat AI

A native Android chat app where you talk to an AI pair programmer (Ollama Cloud)
that reads and edits files in a GitHub repository you select — with **every
commit landing on a safe working branch, never on `main`**, and a diff +
Approve/Reject gate before anything is written.

## Highlights

- **AI tool-calling loop** — the model answers with strict JSON
  (`read_file` / `write_file` / `create_pull_request` / `check_ci_status` /
  `reply`). It can read files it needs, propose edits, check CI, and (only
  after a committed write) open a PR — programmatically guarded, not just
  prompt-hinted.
- **Human-in-the-loop commits** — every proposed change is shown as a
  color-coded line diff with **Approve & commit / Reject** before the GitHub
  API is called.
- **Never pushes to main** — each repository gets a dedicated working branch
  (`ai-chat/<session-id>`), created from the default branch HEAD, visible as a
  chip in the chat screen. A **Create pull request** action opens a PR into the
  default branch; merging stays a manual step on GitHub.
- **Autonomous CI fix loop** — an opt-in "Auto-fix until CI passes" toggle runs
  edit → commit → poll CI → read the real Actions failure log (tail ~8k chars)
  → fix → repeat up to 5 attempts, under a foreground service, streaming every
  step as chat bubbles.
- **Multi-provider LLM routing** — Ollama Cloud plus any OpenAI-compatible
  endpoint (Groq, Cerebras, OpenRouter, Together.ai, Fireworks, custom), preset
  provider dropdowns, live `/models` dropdowns (manual fallback), OpenRouter
  free-model-first sorting with a default "free only" filter, a reorderable
  priority list, and automatic fallback on rate limits with a chat note.
- **Multi-service connections** — Settings is a list of typed connections:
  GitHub (PAT), Cloudflare (API token + Account ID, read-only status), Vercel
  (read + trigger deployment), and Firebase (Web API key or Service Account
  JSON with OAuth2 token exchange), each with a Test Connection check.
- **Secure credential storage** — all keys / tokens / Service Account JSON live
  in `EncryptedSharedPreferences` (Android Keystore-backed AES-256), excluded
  from backups. Chat history is persisted in Room per repository.
- **Polished UI** — drawer shell with searchable chats, general/repo chat modes,
  repos + file-tree browser, markdown rendering with ChatGPT-style code cards
  (copy + collapse), dark mode, shared-element transitions, animated bubbles,
  empty/error states, and a top-of-chat provider switcher.

## Architecture

MVVM + Clean Architecture + Hilt, in four Gradle modules:

```
app/          Compose UI (screens, navigation, theme) + ViewModels
core/model/   Pure Kotlin domain model, JSON action parser, diff engine,
              prompt builder, session ids — fully unit-tested
core/domain/  Use cases, repository contracts, the AI editing orchestrator
              (tool loop + approval gate) — unit-tested with fakes
core/data/    Room, EncryptedSharedPreferences, Retrofit/OkHttp, GitHub and
              Ollama API clients, Hilt DI module
```

All dependency versions live in `gradle/libs.versions.toml` (version catalog);
nothing is hardcoded in build scripts.

## Stack

- Kotlin 2.0.21, Jetpack Compose (BOM 2025.01.00, Material 3), minSdk 24,
  target/compile SDK 35
- Room, EncryptedSharedPreferences (androidx.security-crypto)
- Retrofit + OkHttp + kotlinx.serialization (coroutines)
- Hilt (KSP), Navigation Compose, Gradle 8.11.1 / AGP 8.7.3
- GitHub Actions CI builds a debug APK and runs unit tests on every push

## Getting started

1. Open the project in Android Studio (Koala or newer).
2. Create an [Ollama Cloud](https://ollama.com) API key and a
   [GitHub personal access token](https://github.com/settings/tokens) with the
   **repo** scope.
3. Run the app, open **Settings**, paste both, pick a model
   (e.g. `gpt-oss:120b-cloud`) and use **Test connection** for each service.
4. **Browse repositories**, pick one, and chat. Approve the diff when the AI
   proposes a change; create a PR when you're done.

## How a turn works

1. User sends a message → session is ensured in Room.
2. The working branch `ai-chat/<session-id>` is created from the default
   branch HEAD if it doesn't exist (via `POST /git/refs`); otherwise it's
   reused, so all edits accumulate in one reviewable branch.
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

`.github/workflows/android.yml` does two things:

1. **Build on every branch** — `on.push.branches: ["**"]` and `pull_request`
   trigger lint, `:app:assembleDebug`, the full unit test suite
   (`:core:model:test`, `:core:domain:test`, `:core:data:testDebugUnitTest`,
   `:app:testDebugUnitTest`), connected tests, and a dependency scan. This is
   what makes the app's `check_ci_status` / auto-fix loop able to see CI on any
   AI-created working branch.
2. **Auto-merge into `main`** — once an `ai-chat/*` working branch is **green**,
   a second job merges it into `main` with a merge commit (`--no-ff`) and
   pushes. No manual push is needed, and `main` can never receive a red commit
   because the merge job only runs after the build job succeeds. The merge job
   is explicitly filtered to `refs/heads/ai-chat/*`.

Note: the app itself still never writes to `main` — AI commits always land on
`ai-chat/<session-id>` working branches. The CI auto-merge only moves the
*application code* from the green working branch into `main`. If you ever
enable branch protection on `main`, the auto-merge job will fail loudly; in
that case comment out the job and merge via pull request instead.

## Project layout

```
.github/workflows/android.yml   CI: assembleDebug + unit tests + APK artifact,
                                then auto-merges the green working branch
                                into main
gradle/libs.versions.toml       Version catalog
gradle/wrapper/                 Gradle wrapper (jar included)
settings.gradle.kts             Module graph
build.gradle.kts                Root build script
app/                            Compose UI, navigation, theme, ViewModels
core/model/                     Pure model + diff engine + parser (unit tests)
core/domain/                    Use cases + AI editing orchestrator (unit tests)
core/data/                      Room, secure prefs, Retrofit APIs, Hilt module
```

## Branch safety notes

- The app **never** omits the `branch` field on content writes, so commits can
  never silently default to `main`.
- File paths from the model are sanitized (no `..`, no `.git`).
- If a file changed upstream, GitHub returns 409 and the app asks you to simply
  resend the message so the file is re-read first.

## Background AI turns

In-flight turns run inside `AiTurnCoordinator` (application scope) behind an
`AiTurnService` foreground service (`dataSync`) so leaving the app (home /
screen off) does not cancel the Ollama/GitHub network call. Results are still
persisted in Room — reopen chat to see the completed reply/diff/PR.

Even with a foreground service, some OEMs (especially Xiaomi/MIUI, Vivo, Oppo)
add a manual battery-optimization / Autostart whitelist. Settings has a one-line
tip and a button that opens this app’s system details page
(`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`) so you can set battery to
“No restrictions”.

## Auto-fix until CI is green

Chat has an opt-in checkbox **“Auto-fix until CI passes”**. When enabled, the
send path runs `AutoFixLoop` under the same foreground service:

1. One AI turn (read/write) — writes are auto-approved so the loop can run unattended
2. Poll GitHub Actions on the working branch (~15s backoff, up to ~12 minutes)
3. On failure: fetch the failed job’s real log (tail ~8k chars), re-prompt the model, commit again
4. Stop on green, or after 5 attempts with an honest summary + next-step question

Never claims success it didn’t achieve. Progress is written as chat bubbles and
shown on the FGS notification (`Attempt 2/5 — CI failed, fixing…`).
