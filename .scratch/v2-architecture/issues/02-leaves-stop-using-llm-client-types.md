# 02: Leaves stop using `LlmClient` types

**Parent:** `.scratch/v2-architecture/PRD.md` (Migration — v1 freeze exception) · ADR 0020 D9
**Blocked by:** None (can start immediately)
**Status:** done (2026-10-20)

**What to build:** Leaves that v2 will reuse stop depending on v1's LLM client types. Pure type moves; the request
bytes on the wire are unchanged.

- A leaf-owned image part type replaces the LLM client's content part in tool outputs, MCP tool results, and image
  processing. v1's client converts at the point where it builds messages.
- Image reference parsing returns leaf-neutral parts (text plus images); v1 builds its user message from them.
- The context profile and the token budget can be built from a context-window size; v1 call sites pass the client's
  window.
- Render is out of scope here (ticket 03).

- [x] No package outside `render`, `llm`, and the v1 agent / CLI assembly imports `LlmClient`.
- [x] Request bodies are byte-identical: the existing image-input adapter test passes unchanged.
- [x] `mvn test -Pquick` is green; tests change only in imports and type names.

## Comments

**2026-10-20 — done (leader review).**

- **How "v1 agent / CLI assembly" was read** (for the first acceptance box): `LlmClient` may still appear in
  `com/cafecli/{llm,render,agent,cli,hitl}/` and in the v1-spine classes that sit in leaf packages and that v2 deletes
  or replaces — `tool/ToolRegistry`, `tool/ToolBatchExecutor`, `memory/{MemoryManager,ContextCompressor,
  ConversationHistoryCompactor,TokenBudget}`. `TokenBudget(int)` already took a window; only `MemoryManager`
  constructs it and its estimator works on v1 messages, so it stayed. Nothing else in `src/main` mentions `LlmClient`.
- New leaf types: `image/ImagePart(base64, mimeType)` — leaves only ever produce base64 images, so no url / text forms;
  null/blank mime → `image/png` in the constructor, same moment as the old `ContentPart.imageBase64`.
  `ImageReferenceParser.parse(...)` → `ParsedInput(text, images)` (empty images = plain-text message).
  `ContextProfile.forWindow(window, promptCachingSupported, promptCacheMode)`.
- v1-side bridges in `llm/`: `ContentPart.image(ImagePart)` (the one conversion), `UserMessages.fromInput(input,
  baseDir)` (reproduces the parser's three branches), `LlmContextProfiles.from(LlmClient)` (null client → 128k, no
  caching, `"none"`, as before).
- `TokenUsageFormatter.format(...)` had no callers at HEAD; deleted rather than re-typed. `estimatedCostCny` now takes
  the provider name. (`docs/agents-reference.md:44` still describes that `📊 Token` line — it was already stale.)
- Verified by the leader: `-Pquick` 1352/0/0/0; 12 named suites incl. `AbstractOpenAiCompatibleClientImageInputTest`
  (assertions untouched), `ToolRegistryTest`, `McpServerManagerTest`, `AgentTraceWriterTest` 187/0/0/0; tool
  definitions byte-identical; literal multiset changed only by `+"image/png"` (ImagePart normalization) and the dead
  format string. The implementer also A/B'd old vs new class loaders on 50 inputs (messages, MCP images, profiles,
  cost) — all equal.
- Test edits beyond pure renames, accepted: `McpClientTest:137` now asserts `"image_base64"` on
  `LlmClient.ContentPart.image(part).type()` (an `ImagePart` has no type — it is base64 by construction, so the check
  is now carried by the type system); the image-input adapter test builds its expected part through
  `ContentPart.image(ImageProcessor.toImagePart(...))`.
- **Follow-up for ticket 16 / 29:** `ImageReferenceParserTest` now drives `UserMessages.fromInput` (v1). Nothing tests
  `ImageReferenceParser.parse()` directly; when v1 tests are deleted the parser loses coverage unless the test is
  re-expressed against `parse()` / seam 1.
