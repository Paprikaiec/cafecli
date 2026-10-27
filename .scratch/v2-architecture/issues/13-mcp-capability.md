# 13: MCP capability

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` MCP) · ADR 0020 D6 · ADR 0009
**Blocked by:** 05, 11
**Status:** done

**What to build:** Configured MCP servers work in v2 as in v1: their tools show up and can be called, large results
are referenced instead of pasted, and the resource index is in the system prompt.

- Session-scoped: reuse the MCP server manager (through the sink from ticket 01) — connections, `tools/list`,
  `list_changed`, resources.
- Every connected MCP tool is exposed **eagerly** as `mcp__{server}__{tool}`. Deferred MCP Tools / `tool_search` are
  out of scope; keep the per-agent tool list in `prepareRequest` as the place they would plug in.
- MCP result referencing stays with the MCP tool adapter; the result scope is per agent and is cleared on `/clear`
  and task reaping.
- The resource index lives in the system prompt and triggers a rebuild when the MCP set changes.
- Port MCP mentions in user input and the `/mcp` commands. Project `mcp.json` stays not trust-gated (known
  asymmetry — preserve).

- [x] Seam 1 with a stub MCP server: its tools are in the recorded tool list; a call round-trips; an `isError`
      result maps to `ERROR`.
- [x] Seam 1: a large result is referenced (compact summary in the request, payload under the agent's scope);
      `/clear` removes the scope.
- [x] Seam 1: `list_changed` adds a tool from the next iteration on and rebuilds the system prompt once.
- [x] The system prompt is identical across runs while the MCP set is unchanged.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-21 (from ticket 08 review).** Leaf adapters stamp `harness.ToolExecution` provenance in result details for `AuditCapability`: RETURNED (including business ERROR), POLICY_DENIED, or FAILED. Preserve this for MCP execution; do not infer audit outcome from status/text. `SessionRegistrar.onClose(AutoCloseable)` now owns reverse-order session cleanup, including failed construction. Register MCP resources there; `AgentSession.close()` stops the active run before cleanup.

**2026-10-21 (from ticket 10 review).** SessionRegistrar.sessionId() supplies one stable session-UUID (ticket10), already used in Pre/Post hook payloads. Reuse this session identity for MCP result persistence / scope paths rather than creating a second independently named session; preserve per-agent scopes and cleanup.

**2026-10-21 (from ticket 11 review).** Ticket 11 adds AgentScope.systemPromptInvalidator() and AgentSession.rebuildSystemPrompt(): repeated invalidations coalesce at prepareRequest, so set changes during a run can take effect next request. PromptContext is still empty until this/skill ticket contributes static indices. SessionRegistrar.onClear(Runnable) runs during idle clear before the freshly assembled main agent is installed; use it for old main-scope cleanup, and keep SessionRegistrar.onClose ownership for session connections.

**2026-10-21 (from ticket 12 review).** Ticket 12 adds pure sourceProjectionStage (source ranges first) then ordinary projectionStage, ordered within each phase; iterationBoundaryWriter/contextOverflowWriter receive immutable snapshots and see earlier returned writer entries. Use ordinary view stages for hiding/rewriting; do not shift source indices before source normalization. messages()/projectMessages now share projected chronology and visibility. systemPrompt() reads the cached prompt on the loop thread (may consume explicit invalidation); notification threads should only request invalidation, not rebuild/read it.


**2026-10-22 (leader acceptance).** Added session-scoped MCP discovery/connections through the existing manager/sink, eager namespaced tools, resources/mentions and `/mcp` commands, with explicit captured configuration sources and the existing ungated project config. Each request fixes its definitions and executable adapters together; list changes become usable next iteration. The resource index contributes through the cached prompt seam, repeated invalidations coalesce, and unchanged ordinary runs preserve prompt bytes. Actual composition expands MCP mentions before ordinary UPS. Local `@path` and the pre-existing steering-preparation gap are explicitly assigned to tickets23/27; the earlier handoff's contrary v1 claim is corrected in ticket10, without changing the parity whitelist.

Each agent reuses the existing per-call25k / batch125k largest-first budget and canonical session-UUID/main|task_N paths. Large raw payloads remain intact, digests redact sensitive fields, and final publication retains typed status and execution provenance, including returned business ERROR. Immediate ToolEnd remains available to unblock sibling execution; changed normalization emits final source-order ToolBatchEnd before atomic assistant/result append. ADR0020 records the event distinction and rejected delayed-completion/progressive-budget/generation-suffix alternatives; tickets20/24 describe publication obligations. AgentScope.onClose provides idle-only, reverse-order owned cleanup with partial-construction and failed-clear safety. Unused replacement agents own no old-scope files; synchronization and closed admission prevent late scope writes; normal shutdown removes the session tree after manager cleanup. The delayed HTTP cancellation regression was already green before hardening, so it is not an in-persistence red; mutual exclusion was source-reviewed without adding a test-only public constructor.

A narrow necessary leaf ownership correction assigns a client before initialization/discovery, explicitly owns its notification router, and attempts all server/resource cleanup despite failures, retaining suppression. Normal wire/output behavior remains unchanged; HTTP transport was not changed. Existing virtual-resource text-return behavior remains normal-return audit provenance. Both independent review axes found the persistence/cleanup gaps, then rereviewed their corrections clean; both also reviewed the final stdio fixture correction clean.

Implementer broad named suites: **444/0/0/0**; final focused29/0/0/0. Independent leader quick: **1686/0/0/0**; final independent named core/harness/app/MCP/registry/architecture: **444/0/0/0**, including architecture8, manager8 and registry78. The first independent named run was444/1/0/0: an existing stdio fixture emitted before listener registration. Delayed registration reproduced that exact timeout twice; a child stdin handshake passed with the same delay, then the diagnostic delay was removed. Original output/timeouts/assertions remain unchanged; the transport product code was untouched. Clean package passed. Actual packaged local project-MCP scenarios passed mention expansion, per-call and aggregate referencing, raw payload data, canonical paths, business-error audit, session-tree cleanup, and durable audit; packaged hook/session-ID/memory/budget/guard scenarios also passed. No external services or real keys, no render implementation changes or real-terminal claim. Local commit only; no push.
