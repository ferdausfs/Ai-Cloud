# Audit Notes — Sections 1–11

## Status legend
- DONE = fully implemented and verified in code
- PARTIAL = implemented but with noted gaps
- MISSING = not started

## 1. Core reliability
- Blank error messages: DONE — `ErrorMapping.kt` guards blank `e.message()` (HTTP/2).
- Ollama error format (`{"error":"..."}`): DONE — `OllamaErrorDto` + mapping used.
- GitHub nested 422 errors: DONE — `errors[].message` surfaced before top-level.
- Ollama NDJSON streaming: DONE — `OllamaRepositoryImpl` splits lines, decodes each.
- Ollama timeouts (30/120/60s): DONE — set in `DataModule.provideOllamaApi`.

## 2. Attachment support
- IconButton picker: DONE.
- Text files read + prepended via PromptBuilder (~80k cap): DONE.
- Images only if vision supported, else text note: DONE.
- Removable filename chip: DONE.

## 3. Full agent toolset
- `create_pull_request`: DONE (AiAction + orchestrator + API + use case).
- `check_ci_status`: DONE (lists workflow runs, emits CiStatus).
- Read CI failure logs (jobs → failed job → logs, ~8k tail): DONE in `AutoFixLoop.fetchFailureLog`.

## 4. Autonomous fix-until-green loop
- Opt-in toggle near send: DONE (checkbox in BottomBar).
- Loop (edit→commit→poll CI→fix→repeat, cap 5): DONE in `AutoFixLoop`.
- Streams every step as chat message: DONE via `AutoFixProgress` events + `postStatus`.
- Honest give-up summary, never claims success: DONE (`buildGaveUpMessage`).
- Runs in foreground service: DONE (coordinator + AiTurnService FGS).

## 5. Background survival
- FGS with dataSync type + persistent updating notification: DONE.
- Turn results land in Room regardless of foreground: DONE (ChatRepositoryImpl).
- Battery-optimization OEM tip + button to app battery settings: DONE in Settings.

## 6. CI workflow trigger coverage
- `.github/workflows/android.yml` `on.push.branches: ["**"]`: DONE/CONFIRMED.
- Auto-merge job scoped to `ai-chat/**` — fine, that's the merge step, not the trigger.

## 7. Multi-service connections (GitHub, Cloudflare, Vercel, Firebase)
- Settings as typed Connection entries: PARTIAL — LLM (Ollama/OpenAI-compatible) + GitHub exist; Cloudflare/Vercel/Firebase MISSING.
- GitHub PAT migrated into structure + test: DONE.
- Cloudflare API Token + Account ID, read-only zones/workers status: MISSING.
- Vercel API Token, read (deployments) + write (trigger deploy): MISSING.
- Firebase Project ID + Web API Key / Service Account JSON picker, doc OAuth2 limit: MISSING.
- Encrypted store for all creds: DONE (EncryptedSharedPreferences); new creds just Need adding to AppSettings/store.

## 8. Multiple LLM providers + rate-limit fallback
- `LlmService` interface + `LlmRouterImpl` fallback on 429: DONE.
- `OpenAiCompatibleRepositoryImpl` for Groq/Cerebras/OpenRouter/Together/Fireworks: DONE.
- Provider dropdown presets auto-fill base URL: DONE.
- Model dropdown fetched live from `/models`, fallback manual: DONE.
- OpenRouter free/paid `:free` badge, free-first sort, free-only default ON: DONE.
- `providerOrder` reorderable + auto-fallback note in chat: DONE.
- Manual provider switch chip in chat top bar: DONE.
- Ollama model dropdown: PARTIAL — curated `KNOWN_OLLAMA_CLOUD_MODELS`, no live Ollama listing endpoint used (acceptable, noted).

## 9. Markdown + code-block rendering
- Real markdown (headings/bold/lists/links): DONE via `MarkdownProse`.
- Fenced code card (lang label, mono, h-scroll, syntax color, copy w/ checkmark): DONE.
- 15+ line blocks collapse with Show more: DONE.
- Diff approval view as styled card: DONE (`DiffView`).
- CI log excerpts rendered as markdown/code: DONE (fenced blocks + `preferLogCodeBlock`).
- Dark mode via theme colors: DONE.

## 10. Navigation / app structure
- Drawer-based shell (hamburger, searchable chats, + new, Repos/Settings bottom): DONE.
- General chat mode + "Chat with a repo" choice: DONE.
- Repos section (list + file-tree browser + code-block viewer + "Chat about this repo"): DONE.
- Repo stars NOT displayed in RepoCard: PARTIAL (field exists on RepoSummary, not shown).
- Model/provider switcher at top of active chat: DONE.
- Understated bubbles, distinct code blocks: DONE.
- Room migration for session schema: DONE (MIGRATION_1_2).

## 11. Branch safety
- Never commit/push to main; all writes to per-session `ai-chat/{id}` branch: DONE.
- `commitFile` always passes branch param; `ensureWorkingBranch` creates from default HEAD: DONE.
- PR creation requires explicit action (button or model-issued create_pull_request after completion): DONE.
- Verified across all new tool paths: DONE.

## Primary gap to fix
- Section 7: add Cloudflare, Vercel, Firebase connection types, APIs, repositories, Settings UI, encrypted storage. This is the only MISSING section of substance.

## Minor polish
- Show repo stars in RepoCard.
- Note in Ollama model picker that listing is curated when live endpoint unavailable.
