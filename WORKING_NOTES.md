# RepoChat Audit Notes (master task pass)

## Section status (done / partial / missing)

1. **Core reliability fixes** — DONE. `ErrorMapping.kt` already guards blank HTTP reason
   phrases, parses Ollama `{"error"}` separately from GitHub nested `errors[].message`,
   Ollama `chat()` returns raw `ResponseBody` and splits NDJSON lines, and the Ollama
   OkHttp client has 30s/120s/60s timeouts. Unit tests cover blank reason, Ollama error,
   GitHub 422 nested, blank body fallback.
2. **Attachment support** — DONE. `ChatScreen` has an attach `IconButton`, OpenDocument
   picker, text files loaded as ~80k prompt context via `PromptBuilder`, images sent only
   when `PromptBuilder.modelSupportsVision`, removable filename chip, max 5 MB guard.
3. **Full agent toolset** — NOW DONE. `create_pull_request`, `check_ci_status`, and CI
   failure-log reading (`listJobsForRun` + `getJobLogs` -> last ~8k chars) all exist and are
   exercised by `AutoFixLoop`. `create_pull_request` is now hard-enforced: the orchestrator
   refuses (and feeds the reason back to the model) unless the session already has ≥1
   APPROVED `WRITE_FILE`, with unit test coverage.
4. **Autonomous fix-until-green loop** — DONE (behind the opt-in toggle). Streams every
   step as chat bubbles, polls CI, fetches real failure logs, honest give-up summary. Runs
   under the FGS via `AiTurnCoordinator`.
5. **Background survival / FGS** — DONE. `AiTurnService` is `dataSync` with a persistent
   updating notification (`Attempt N/M — …`), Room-persisted results, settings battery tip +
   settings-details button, manifest permissions present.
6. **CI workflow trigger coverage** — DONE. `.github/workflows/android.yml` already uses
   `on.push.branches: ["**"]`, so pushes to any AI-created working branch trigger CI.
7. **Multi-service connections (GitHub / Cloudflare / Vercel / Firebase)** — NOW DONE.
   Settings is now a list of typed `Connection`s: GitHub (PAT), Cloudflare (API token +
   Account ID, read-only zones/Workers status; Workers deploys intentionally out of v1),
   Vercel (API token, read deployments + trigger deployment), Firebase (Project ID + Web
   API key OR Service Account JSON with OAuth2 token exchange). Each has a credential form
   and Test Connection. All stored encrypted; legacy flat GitHub PAT is migrated into a
   `GITHUB` connection without losing its value. There is also a visible Vercel
   "Trigger production deployment" button in the Vercel editor.
8. **Multiple LLM providers + rate-limit fallback** — DONE. `LlmService` router, presets,
   live model dropdown (falls back to manual), free/paid OpenRouter sort + default
   free-only filter + badges, `providerOrder`, manual provider switcher in chat header.
9. **Markdown + code-block rendering** — DONE. Markdown prose, fenced code cards with
   language label / horizontal scroll / syntax colors / copy w/ checkmark / collapse ≥15
   lines; same chrome on diff approval view; CI log excerpts rendered as log code cards;
   theme-derived colors.
10. **Navigation / structure** — DONE. Drawer shell (`HomeScaffold`), searchable chats +
    new-chat (General vs Chat-with-repo), Repos list + read-only file-tree browser using the
    code-block viewer, provider switcher top-of-chat, Room migration `1→2` for
    `mode/title/updated_at` (no wipe).
11. **Branch safety** — DONE (`ensureWorkingBranch` returns `ai-chat/<session>`, every
    content write passes `branch`, PRs target `defaultBranch`, workflow auto-merge only for
    `ai-chat/*`, main is never pushed by the app). New v1 service APIs do not touch git.

## What this pass changed
- Section 7: typed connections for GitHub, Cloudflare, Vercel, Firebase with credential
  forms + Test Connection, encrypted store migration from the flat GitHub PAT, status
  read endpoints (Cloudflare zones/Workers, Vercel deployments), Vercel write (trigger
  deployment), Firebase Web-API-key and Service-Account OAuth2 token-exchange paths.
- Section 3: hard `create_pull_request` guard — a PR tool call is rejected (fed back to the
  model) unless the session already has ≥1 APPROVED `WRITE_FILE`.
- Small exhaustive-`when` updates so the new `ConnectionType` values compile everywhere.
- Section 7 unit tests: `ErrorMappingTest` for Cloudflare/Vercel/Firebase bodies,
  `ExternalServicesImplTest` for per-provider URL/DTO parsing (Cloudflare zones/Workers,
  Vercel list vs `teamId` query, Firestore project via Web key, non-service rejection),
  and `FirebaseOAuthTest` for JWT compact shape, `iss/aud/scope/iat/exp` claims, no
  over-escaping, RS256 signature verification, and `parse()` field extraction.
- Fixed a real Firebase bug: the PKCS#8 PEM decoder now uses standard (not URL-safe)
  base64 so real `+`/`/` private keys decode; the JWT segments still use URL-safe.
- Fixed a Settings deletion bug: deleting the only GitHub row now clears the legacy flat
  PAT (`persist()` writes `primaryGithub?.apiKey.orEmpty()` and `deleteConnection` clears
  it), so the store migration cannot resurrect a deleted GitHub connection on next launch.
- Added the Vercel "Trigger production deployment" action (wired through
  `ExternalServices.triggerDeployment`) with the `settings_vercel_trigger` string.
- README refreshed for current CI/branch logic, multi-provider, multi-service, and UI.

