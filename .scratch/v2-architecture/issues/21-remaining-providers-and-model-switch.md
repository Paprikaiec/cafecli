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
