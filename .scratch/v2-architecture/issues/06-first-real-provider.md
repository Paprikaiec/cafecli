# 06: First real provider

**Parent:** `.scratch/v2-architecture/PRD.md` (`ai` layer; seam 2) · ADR 0020 D3, D8
**Blocked by:** 04
**Status:** ready-for-agent

**What to build:** v2 talks to a real model: the OpenAI-compatible base adapter plus DeepSeek, chosen through today's
provider configuration and API-key environment variable.

- The adapter converts neutral messages and tool definitions to the wire format and back, streams tool-call deltas,
  and owns the DeepSeek `reasoning_content` round-trip (thinking from an assistant tool-call message goes back on the
  next request).
- Model registry: a bundled table with today's values (context window etc.) for the default models of all five
  providers, with config overrides. The other providers' adapters come in ticket 21; selecting one under the flag
  prints "not available in v2 yet".

- [ ] Seam 2 (local HTTP server, recorded fixtures): the request body matches the wire format; recorded SSE bytes
      produce the expected events, including tool-call deltas and usage with cached tokens; the reasoning round-trip
      holds.
- [ ] The registry returns today's context window for each provider's default model; a config override wins.
- [ ] Optional, with a key: a real `run --file` under the flag answers a question that needs `read_file`.
- [ ] `mvn test -Pquick` and the architecture tests are green.

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
