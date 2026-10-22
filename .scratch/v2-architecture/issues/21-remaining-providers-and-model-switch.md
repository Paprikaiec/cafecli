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

- [ ] Seam 2 per provider: request body, SSE → events, reasoning round-trip where it applies, usage with cached
      tokens.
- [ ] Provider selection matches `LlmClientFactoryTest`'s cases.
- [ ] Seam 1: after `/model`, the next recorded request uses the new model id and the compaction threshold follows the
      new window.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-21 (from ticket 06 review).** `ai.ModelRegistry` now supplies the five default records and `resolve(provider,id)`; unknown model ids inherit that provider's metadata, and typed config supplies optional `models` overrides. `ConfigLoader` ports the existing config/env/.env and provider-alias/key-fallback behavior. `app.Composition` currently creates a DeepSeek provider and the selected model (or fake); its unsupported-provider branch is temporary. Extend the new OpenAI-compatible adapter through provider quirks, not through core branches. `AgentFactory` still holds one Provider, so model/provider switching and cross-provider profiles need the routing/factory solution already noted on ticket 17; changing only the Model record must not send another provider's model to DeepSeek's endpoint.

**2026-10-21 (from ticket 11 review).** Ticket 11 exposes deferred prompt invalidation, but AgentSession.mainProfile and AgentScope.model() currently retain the built agent profile. A model switch must update actual agent metadata and preserve the log while rebuilding the prompt; do not let memory recall / later budget metadata stay bound to the previous model.

**2026-10-22 (from ticket 15 integration).** WebCapability reads AgentScope.model() when executing its reusable
WebTools adapter to choose StepSearch for Step step-3.7-flash. Switching models must update this actual selection as
well as provider routing, recall and compaction budgets; do not leave Web's scope bound to the original profile.
Freshness is a run-start writer after Hooks and invokes through the same immutable executable snapshot.
