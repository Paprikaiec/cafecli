# 06: First real provider

**Parent:** `.scratch/v2-architecture/PRD.md` (`ai` layer; seam 2) · ADR 0020 D3, D8
**Blocked by:** 04
**Status:** done (2026-10-21)

**What to build:** v2 talks to a real model: the OpenAI-compatible base adapter plus DeepSeek, chosen through today's
provider configuration and API-key environment variable.

- The adapter converts neutral messages and tool definitions to the wire format and back, streams tool-call deltas,
  and owns the DeepSeek `reasoning_content` round-trip (thinking from an assistant tool-call message goes back on the
  next request).
- Model registry: a bundled table with today's values (context window etc.) for the default models of all five
  providers, with config overrides. The other providers' adapters come in ticket 21; selecting one under the flag
  prints "not available in v2 yet".

- [x] Seam 2 (local HTTP server, recorded fixtures): the request body matches the wire format; recorded SSE bytes
      produce the expected events, including tool-call deltas and usage with cached tokens; the reasoning round-trip
      holds.
- [x] The registry returns today's context window for each provider's default model; a config override wins.
- [ ] Optional, with a key: a real `run --file` under the flag answers a question that needs `read_file`.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04a review).** The `ai` layer's `AssistantStream.Emitter` allows **one open block at a
time**: `toolCallStart(id, name)` needs both values up front, `toolCallDelta` has no key, and any text/thinking delta
closes an open tool call (a following `toolCallDelta` then throws → the stream fails FATAL). OpenAI-compatible SSE
does not fit that directly — v1's `AbstractOpenAiCompatibleClient.mergeToolCallDeltas` keys calls by `index`, appends
the name across deltas, takes the id whenever it arrives, and some providers interleave whitespace content between
argument chunks. Either buffer per `index` in the adapter (simplest; costs nothing on providers that stream one call at
a time) or extend the `Emitter` with keyed tool-call deltas — the **`StreamEvent` contract core consumes must not
change** either way. Pin the chosen behavior with recorded SSE fixtures (interleaved calls, late id, whitespace content
between argument chunks). Also: there is no stop reason for `content_filter`; `done()` rejects ERROR, so map it via
`fail(FATAL, …)` (or add a stop reason, if v1 distinguishes it).

**2026-10-21 — done (leader review).**

- `ai.openai.OpenAiCompatibleProvider` maps neutral messages, tools and images to the existing wire shape; `DeepSeekProvider` owns reasoning_content history. Every request carries its Model record. Indexed tool calls buffer until finish, then replay their original argument chunks in order; late ids, chunked names, interleaving and whitespace do not change the neutral StreamEvent contract. Usage includes all five existing cache-token fields.
- `ai.ModelRegistry` supplies the five existing default ids/windows and per-registry overrides. Typed `ProviderSelection` carries credentials (redacted toString), model/base URL, metadata and HTTP timeouts. `ConfigLoader` preserves provider aliases, supported-provider key fallback, config > environment > project .env > home .env precedence, and Kimi/Moonshot fallback. `Composition` chooses DeepSeek and the configured model, or fake; the other four adapters remain unavailable.
- DeepSeek's production endpoint remains fixed, and configured baseUrl/temperature/maxTokens remain ignored, matching v1. Local tests inject a localhost endpoint directly into the adapter. Added optional user-config `models` metadata overrides; documented the format and experimental behavior in the reference and env example. No v1 execution code changed.
- Initial red evidence: missing adapter/registry, configuration key/fallback/metadata mismatch, transport classified FATAL instead of RETRYABLE, and CLI still refusing DeepSeek. The real-adapter session test verifies read_file, private model selection, reasoning history and tool result on the second wire request. Standards: no findings. Spec: no findings.
- Leader independently ran quick: **1523/0/0/0** (all 8 architecture rules included). Mutation of the HTTP cancellation registration initially exposed a timing gap in the body-read test; readiness was strengthened. With the hook removed, both blocked-header and blocked-body cases now error with TimeoutException; after restoring production, focused provider/session/config/registry/architecture tests: **48/0/0/0**. The readiness change is test-only. Implementer final broader targeted run: **166/0/0/0**.
- Leader built the clean jar and checked the packaged fake read/answer path (exact stdout, empty stderr, exit 0) and missing DeepSeek credential path (exit 1). The optional real-key run was not attempted; all network verification used localhost. Follow-ups on 21/22 cover remaining provider/model routing and full error taxonomy/retries.
