# RepoChat AI

A native Android chat app where you talk to an AI pair programmer (Ollama Cloud)
that reads and edits files in a GitHub repository you select — with **every
commit landing on a safe working branch, never on `main`**, and a diff +
Approve/Reject gate before anything is written.

## Highlights

- **AI tool-calling loop** — the model answers with strict JSON
  (`read_file` / `write_file` / `reply`). It can request files it needs; those
  contents are fed back into the context until it writes or replies.
- **Human-in-the-loop commits** — every proposed change is shown as a
  color-coded line diff with **Approve & commit / Reject** before the GitHub
  API is called.
- **Never pushes to main** — each repository gets a dedicated working branch
  (`ai-chat/<session-id>`), created from the default branch HEAD, visible as a
  chip in the chat screen. A **Create pull request** action opens a PR into the
  default branch; merging stays a manual step on GitHub.
- **Secure credential storage** — API keys / GitHub PAT live in
  `EncryptedSharedPreferences` (Android Keystore-backed AES-256), excluded from
  backups. Chat history is persisted in Room per repository.
- **Polished UI** — dark mode, intentional color/type system, shared-element
  transitions between repo picker and chat, animated message bubbles, animated
  typing indicator, empty/error states with retry affordances.
- **Robust error handling** — typed errors (401 → Settings shortcut, 403/429 →
  friendly rate-limit message, 409 → re-read guidance, network → retry),
  truncated file trees for large repos, size-capped LLM context.

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

1. **Build** — on every push it runs `:app:assembleDebug` plus the full unit
   test suite (`:core:model:test`, `:core:domain:test`, `:core:data:testDebugUnitTest`,
   `:app:testDebugUnitTest`) and uploads the debug APK as an artifact.
2. **Auto-merge into `main`** — once the working branch
   (`arena/01a04eb6-ai-cloud`) is **green**, a second job automatically merges
   it into `main` with a merge commit (`--no-ff`) and pushes. No manual push is
   needed, and `main` can never receive a red commit because the merge job
   only runs after the build job succeeds. Pushing to `main` does not
   re-trigger the workflow (the push filter only includes the working branch).

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
