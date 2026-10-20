# 15: Web, RAG, and browser capabilities

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` capabilities) · ADR 0020 D4, D6 · ADR 0019
**Blocked by:** 08, 09
**Status:** ready-for-agent

**What to build:** Web search and fetch, semantic code search, and the browser tools work in v2 as in v1, including
the freshness search and the rule that a cancelled or hook-blocked search never silently falls back to a real one.

- Web: `web_search`, `web_fetch`; the freshness web search as a run-start log writer; the StepSearch fallback reads
  status only — usable means non-empty and not `ERROR` / `DENIED`, so `CANCELLED` / `BLOCKED` are passed back as they
  are.
- RAG: `search_code`.
- Browser: `browser_connect`, `browser_disconnect`, `browser_status`, and the browser check joining the permission
  chain from ticket 08.
- Each is its own capability; an agent whose profile doesn't attach one has none of its tools (Capability Closure,
  intentional change 8).

- [ ] Seam 1: a cancelled or hook-blocked StepSearch call triggers no real web search.
- [ ] Seam 1: a freshness-triggering question gets a freshness entry in the first request; others don't.
- [ ] Seam 1: an agent without the browser capability has no `browser_*` tools in its recorded tool list.
- [ ] Existing web, RAG, and browser leaf tests pass unchanged.
- [ ] `mvn test -Pquick` and the architecture tests are green.
