# 13: MCP capability

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` MCP) · ADR 0020 D6 · ADR 0009
**Blocked by:** 05, 11
**Status:** ready-for-agent

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

- [ ] Seam 1 with a stub MCP server: its tools are in the recorded tool list; a call round-trips; an `isError`
      result maps to `ERROR`.
- [ ] Seam 1: a large result is referenced (compact summary in the request, payload under the agent's scope);
      `/clear` removes the scope.
- [ ] Seam 1: `list_changed` adds a tool from the next iteration on and rebuilds the system prompt once.
- [ ] The system prompt is identical across runs while the MCP set is unchanged.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-21 (from ticket 08 review).** Leaf adapters stamp `harness.ToolExecution` provenance in result details for `AuditCapability`: RETURNED (including business ERROR), POLICY_DENIED, or FAILED. Preserve this for MCP execution; do not infer audit outcome from status/text. `SessionRegistrar.onClose(AutoCloseable)` now owns reverse-order session cleanup, including failed construction. Register MCP resources there; `AgentSession.close()` stops the active run before cleanup.

**2026-10-21 (from ticket 10 review).** SessionRegistrar.sessionId() supplies one stable session-UUID (ticket10), already used in Pre/Post hook payloads. Reuse this session identity for MCP result persistence / scope paths rather than creating a second independently named session; preserve per-agent scopes and cleanup.
