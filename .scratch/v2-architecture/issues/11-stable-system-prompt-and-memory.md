# 11: Stable system prompt and memory capability

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` context and memory) · ADR 0020 D4
**Blocked by:** 09
**Status:** done (2026-10-21)

**What to build:** The system prompt stays the same from run to run, so prompt caching can work; long-term memory
still works — `/save` stores a fact, and a later session recalls it — with the recall placed after the user's message
instead of inside the system prompt.

- The system prompt is built when the agent is created and rebuilt only on `/clear`, a model switch, or a skill-set
  or MCP-set change; expose the rebuild trigger so those capabilities can fire it.
- Memory capability: the long-term store (leaf), `save_memory`, and the memory slash commands. Add the slash-command
  registration type to the SPI if it doesn't exist yet; commands are dispatchable through the session.
- Memory recall is a run-start log writer appending a memory-recall entry after the user's message. It reads the
  long-term store only (intentional change 6).
- Port the explicit memory hints as they are. Define the extraction seam (end of run, before compaction, end of
  session) with no implementation, off.
- Writes still happen only via `/save` or an explicit user request.

- [x] Seam 1: across two runs with different recalls, the system prompt in the recorded requests is byte-identical
      and each recall appears after the user's message.
- [x] Seam 1: `/save`, then a new session — the fact is recalled.
- [x] Seam 1: a run without `/save` or an explicit request writes nothing to the store.
- [x] Seam 1: `/clear` empties the log and rebuilds the system prompt.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04b review).** `core.Agent`'s system prompt and model are `final` (set by the builder). A
rebuild trigger (`/clear`, skill-set / MCP-set change) and ticket 21's model switch need either a narrow setter on
`Agent` (between runs only) or the harness building a fresh agent. `/clear` should build a fresh agent anyway — that is
what resets per-agent capability state (PRD user story 23).

**2026-10-20 (from ticket 04c review).** `AgentSession.main` is `final` and the factory is a constructor local:
`/clear` (fresh agent) needs the session to keep the factory and replace `main` between runs.


**2026-10-21 (leader acceptance).** Added the explicitly composed SessionCapability and memory capability, session command registration / idle dispatch, and fresh-agent clear. /clear resets log, undelivered follow-up input, per-agent state and remembered tool/server grants while retaining session identity / owned resources and long-term facts. SystemPrompt caches the initial assembly; agent/session invalidation coalesces at prepareRequest, including a change during a running request, without replacing history. Normal runs keep byte-identical system prompts. Later skill/MCP prompt contributions and model selection remain their tickets.

Memory receives only its MemoryConfig slice, canonicalizes existing project identity through toRealPath as v1, and opens LongTermMemory lazily. LongTermStore delegates the existing long-term retrieval algorithm; MemoryTools exposes the original save_memory leaf through LeafToolAdapter, preserving schema / output / typed execution provenance. MemoryRecall appends after the current user entry with an actual-agent-model-derived budget and is in the actual-composition serialization round-trip. /save, /memory and /mem keep existing scope, aliases and command text: list/delete/clear operate on the whole store, search/recall on visible global/current-project facts. Memory status omits the retired short-term/compressor statistics. Explicit browser-login hints reuse the unchanged leaf and save global before recall. AgentScope.messages() presents registered kinds from the unchanged log, so landed steering/hook text participates and hidden kinds do not. MemoryExtraction defines RUN_END / BEFORE_COMPACTION / SESSION_END only; no implementation or automatic registration is installed.

Observed implementer red-to-green slices: missing recall position, missing commands, unknown save_memory, missing browser hint, stale prompt after invalidation and project facts lost through a symlink. Root source review removed duplicate retrieval logic and caught canonical project identity; Spec review caught loss of visible custom history. That regression was **3/2/0/0** (steering/hook URL lost, hidden already excluded), then **3/0/0/0**, also checking historical log bytes unchanged. Standards' optional duplicate text extraction was removed. Both fresh Standards and Spec rereviews clean. Final implementer focused suites **336/0/0/0**; independent leader quick **1638/0/0/0**; independent named memory / round-trip / session / factory / steering / permission / hook lifecycle / config / memory leaf / registry / architecture suites **226/0/0/0**, including 8 architecture rules. Clean package passed. Actual packaged fake-provider checks verified ordinary run creates no memory file, save_memory persists exact project fact/metadata, explicit browser hint persists exact global fact, and all prior hook/default-off/guard/session-identity/audit scenarios remain green. No real provider/network/key use, v1 source edits, render changes or real-terminal claim. Local commit only, no push.
