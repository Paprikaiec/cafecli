# 21: Remaining providers and `/model`

**Parent:** `.scratch/v2-architecture/PRD.md` (`ai` layer; model as data) · ADR 0020 D8
**Blocked by:** 06
**Status:** ready-for-agent

**What to build:** Every provider v1 supports works under v2, and `/model` switches models mid-session with budgets
following the new model.

- The remaining adapters (GLM, Step, Kimi, FreeLLMAPI) with their quirks inside the adapter, including Kimi's
  thinking-mode `reasoning_content` round-trip.
- Provider selection from config with today's precedence and API-key environment variables.
- Registry entries for every model the five providers expose today.
- `/model` changes the agent's model record; the system prompt is rebuilt; the context threshold follows the new
  window. No client instances are swapped.

- [x] Seam 2 per provider: request body, SSE → events, reasoning round-trip where it applies, usage with cached
      tokens.
- [x] Provider selection matches `LlmClientFactoryTest`'s cases.
- [x] Seam 1: after `/model`, the next recorded request uses the new model id and the compaction threshold follows the
      new window.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-22 (ticket 17 integration checkpoint).** AgentFactory is now explicitly injected and bound once to its
session; ai.RoutingProvider chooses an adapter by Model.provider and rejects an unknown provider with typed FATAL.
Production only registers its currently configured adapter. Populate the router from captured provider configuration
when porting the remaining adapters, preserving session state and existing selection precedence. Default child
profiles currently capture the startup model, while injected profiles may choose another model/provider and literal
system prompt; audit expected default-child behavior on /model against v1 before changing it, and keep explicit
overrides intact. Model metadata changes must reach the actual factory/profile/agent scopes as well as main requests.

**2026-10-21 (from ticket 06 review).** `ai.ModelRegistry` now supplies the five default records and `resolve(provider,id)`; unknown model ids inherit that provider's metadata, and typed config supplies optional `models` overrides. `ConfigLoader` ports the existing config/env/.env and provider-alias/key-fallback behavior. `app.Composition` currently creates a DeepSeek provider and the selected model (or fake); its unsupported-provider branch is temporary. Extend the new OpenAI-compatible adapter through provider quirks, not through core branches. `AgentFactory` still holds one Provider, so model/provider switching and cross-provider profiles need the routing/factory solution already noted on ticket 17; changing only the Model record must not send another provider's model to DeepSeek's endpoint.

**2026-10-21 (from ticket 11 review).** Ticket 11 exposes deferred prompt invalidation, but AgentSession.mainProfile and AgentScope.model() currently retain the built agent profile. A model switch must update actual agent metadata and preserve the log while rebuilding the prompt; do not let memory recall / later budget metadata stay bound to the previous model.

**2026-10-22 (from ticket 15 integration).** WebCapability reads AgentScope.model() when executing its reusable
WebTools adapter to choose StepSearch for Step step-3.7-flash. Switching models must update this actual selection as
well as provider routing, recall and compaction budgets; do not leave Web's scope bound to the original profile.
Freshness is a run-start writer after Hooks and invokes through the same immutable executable snapshot.

**2026-10-23 (ticket20 trace integration checkpoint).** Trace captures the MAIN scope's actual model and cached
system prompt at run end. `/model` must update that scope metadata and invalidate the prompt used by the next request,
so the trace's provider/model and system record describe what the model actually received. Reading
`cachedSystemPrompt()` must remain observational: it must not consume pending invalidation or build an unsent prompt.
Include trace metadata after a switch in the session-seam verification; trace totals still count only completed MAIN
responses in the latest run, while assistant counts follow the final presented history.

**2026-10-23 (implementation decisions; acceptance pending).** Source audit confirms v1's
ForegroundAgentSpawner captures the startup client; Agent.setLlmClient changes the main client but does not replace
that child default. Preserve the captured default child profile and explicit profile overrides, while updating the
main model, capability scope, prompt invalidation and future main replacement profile. Provider routing remains an
injected immutable adapter map; switching changes the model data rather than swapping clients.

Persist `/model` through the already captured config tree/path in the v2 config package, changing defaultProvider and
the explicitly selected model without writing environment-resolved credentials or discarding unrelated fields.
An injected warning sink preserves save-failure reporting. Do not reuse CafeCliConfig's static host-bound path or
environment-reading getters, or add another full config loader. The v1 config class remains unchanged.

**2026-10-23 (alias routing refinement; acceptance pending).** Startup selection can use an alias entry's missing
canonical fields, while later `/model` resolves the canonical provider configuration. Those can select different
wire settings even for the same model id. Preserve both with a generic optional Model.providerRoute (existing
six-argument construction defaults to the canonical provider) and prebuilt immutable canonical/startup routes.
Model.provider remains the canonical identity for trace, Web and model metadata. A switch selects canonical model
data; captured default children keep the startup route. Do not choose routes by object identity or mutable global
adapter state, swap client objects, merge alias-only credentials into canonical command selection, or defer this
compatibility edge as an unexplained parity difference.

**2026-10-23 (review corrections; final checks pending).** Both review axes found one defect before acceptance.
Malformed known provider containers could boot through environment fallback but throw unchecked during explicit-model
persistence; normalize only those known containers, preserving valid siblings and unrelated fields. The command's
legacy context formatter also clamped a configured 4,000-token model to 8,000 while actual compaction used 4,000.
The new exact-window factory shares existing derivation/formatting, leaving v1's clamp and summary unchanged.
Composed regressions observed both failures before their corrections; final review rechecks are clean. Final full,
independent leader and packaged verification still determine acceptance.

**2026-10-23 (leader acceptance).** GLM / Step / Kimi / FreeLLMAPI now use the shared stateless OpenAI-compatible
adapter with v1 endpoint/image/request/history quirks, alongside DeepSeek and fake. Registry records include the
models named by the existing CLI/config examples; arbitrary ids retain provider defaults. The loader captures
canonical provider settings separately from selected startup alias fallback. Composition builds immutable routes
once; optional Model.providerRoute is explicit request data, while Model.provider remains canonical for trace/Web.
Startup aliases and later canonical selection can therefore use different wire settings for the same provider/id.
Default children retain their captured startup model/route and explicit profiles remain independent.

The registered `/model` command preserves history and capability resources, updates the actual MAIN model/scope
metadata and replacement profile, and invalidates the next request's prompt. Recall, compaction, Web StepSearch and
trace follow the selected model; clear retains it. Command admission rejects switching during a run or after close.
The captured config tree/path saves defaultProvider and explicit model choices, preserves unrelated valid fields,
never writes environment credentials, and reports save failure without reverting the active switch. Failed explicit
choices retain v1's in-session configured choice without changing the running model. The shared ContextProfile leaf
preserves v1 forWindow/summary semantics; v2 uses exact model metadata and omits only the retired short-term field
under PRD exception6. V1 spine remains unchanged.

Observed missing-adapter, unavailable startup, missing switch API and GLM vision-metadata reds turned green. Review
found malformed-provider persistence and clamped small-window status defects; composed regressions observed four
unchecked failures and the wrong 8000/7200 output before correction to safe known-container replacement and exact
4000/3600 facts. Both final independent Standards and Spec reviews are clean. Final focused:
**118/0/0/0**; final full237 suites: **1949/0/0/0**, BUILD SUCCESS. Fresh XML:
`/tmp/ticket21-full-final-reports`; logs `/tmp/ticket21-{final-focused-review,full-final,red-malformed-providers,red-small-window}.log`.
Independent leader quick232 suites: **1845/0/0/0**; named125 suites: **988/0/0/0**, including unchanged legacy
LlmClientFactoryTest and ContextProfileTest; clean package passed. Logs:
`/tmp/cafecli-ticket21-leader-{quick,named,package}.log`.

`/tmp/cafecli21-jar-check.py` passed against that actual jar: captured local Step → Kimi read-tool/reasoning round-trip
→ Free routes, exact credentials/options, retained history, prompt template rebuilt on switch, persisted selection
without unknown-field loss, actual trace current-model/prompt and latest-run tokens **40/4/8** with four retained
assistants through the real eval normalizer. Actual CLI then followed the saved Free selection. A separate composed
case verified startup alias wire routing, later canonical route for the same id, exact 4k status and malformed GLM
entry persistence without the environment key. Fixture root:
`/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-ticket21-xixh5kdu`.
Packaged08/10/10-session/11/12/13/14/15/16/17-retained/18/19/20 regressions all passed against the same jar.

The first Python packaged fixture triggered a reusable-connection EOF on its HTTP/1.0 server. Minimization reproduced
it with two plain Kimi requests; targeted evidence showed no interruption/cancellation. Changing only HTTP/1.1, or
explicit connection-close on HTTP/1.0, made the requests pass. The original fixture now uses HTTP/1.1 and passes;
product source/jar stayed unchanged, temporary instrumentation was removed, and ticket22 records the transport-retry
checkpoint. This was fixture transport evidence, not a model-switch behavioral red or a new parity exception.
Diagnosis logs/artifacts: `/tmp/ticket21-diagnosis-http11.log` and `/tmp/ticket21-diagnosis-artifacts/`.

`.env.example`, the reference and ADR record the delivered provider/model surface; issue28 isolates the summary's
exception6 difference. No push, real keys/external services, real-terminal claim, AGENTS edit or unrelated scratch
change. Interactive UI integration remains tickets23–26.
