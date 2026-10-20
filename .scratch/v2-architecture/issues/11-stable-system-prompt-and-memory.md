# 11: Stable system prompt and memory capability

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` context and memory) · ADR 0020 D4
**Blocked by:** 09
**Status:** ready-for-agent

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

- [ ] Seam 1: across two runs with different recalls, the system prompt in the recorded requests is byte-identical
      and each recall appears after the user's message.
- [ ] Seam 1: `/save`, then a new session — the fact is recalled.
- [ ] Seam 1: a run without `/save` or an explicit request writes nothing to the store.
- [ ] Seam 1: `/clear` empties the log and rebuilds the system prompt.
- [ ] `mvn test -Pquick` and the architecture tests are green.
