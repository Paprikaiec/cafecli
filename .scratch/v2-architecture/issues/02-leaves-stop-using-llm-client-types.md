# 02: Leaves stop using `LlmClient` types

**Parent:** `.scratch/v2-architecture/PRD.md` (Migration — v1 freeze exception) · ADR 0020 D9
**Blocked by:** None (can start immediately)
**Status:** ready-for-agent

**What to build:** Leaves that v2 will reuse stop depending on v1's LLM client types. Pure type moves; the request
bytes on the wire are unchanged.

- A leaf-owned image part type replaces the LLM client's content part in tool outputs, MCP tool results, and image
  processing. v1's client converts at the point where it builds messages.
- Image reference parsing returns leaf-neutral parts (text plus images); v1 builds its user message from them.
- The context profile and the token budget can be built from a context-window size; v1 call sites pass the client's
  window.
- Render is out of scope here (ticket 03).

- [ ] No package outside `render`, `llm`, and the v1 agent / CLI assembly imports `LlmClient`.
- [ ] Request bodies are byte-identical: the existing image-input adapter test passes unchanged.
- [ ] `mvn test -Pquick` is green; tests change only in imports and type names.
