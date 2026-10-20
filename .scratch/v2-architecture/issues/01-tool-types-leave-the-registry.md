# 01: Tool definition and result types leave `ToolRegistry`

**Parent:** `.scratch/v2-architecture/PRD.md` (Migration — v1 freeze exception) · ADR 0020 D9
**Blocked by:** None (can start immediately)
**Status:** done (2026-10-20)

**What to build:** The leaf tool groups, the approval policy, the MCP server manager, and the MCP result budget stop
depending on `ToolRegistry`, so v2 can reuse them without importing v1's spine and v1 can later be deleted without
taking them along. This is a pure type move: behavior and the bytes the model sees are unchanged.

- The tool definition, parameter, invocation, and execution-result types become standalone leaf types instead of
  types nested in the registry.
- The mutating-tools list that the approval policy and the audit share moves out of the registry to a leaf-owned
  home.
- The MCP server manager registers, replaces, and removes tools through a small sink interface; v1's registry
  implements it.
- Allowed under the v1 freeze only because it is a behavior-preserving prefactor (PRD, Migration). Don't change any
  behavior while here.

- [x] No tool group, the approval policy, the MCP server manager, or the MCP result budget imports `ToolRegistry`.
- [x] `mvn test -Pquick` is green; tests change only in imports and type names, never in assertions.
- [x] The tool list the model receives is unchanged in content and order.

## Comments

**2026-10-20 — done (leader review).**

- New leaf types in `com.cafecli.tool`: `Tool`, `ToolParam` (was `Param`; still package-private, owns
  `createParameters`), `ToolInvocation`, `ToolExecutionResult`, `ToolExecutor`, `MutatingTools.NAMES` (was
  `ToolRegistry.MUTATING_TOOLS`). `com.cafecli.mcp.McpToolSink` holds exactly what `McpServerManager` calls:
  `getAuditLog()`, `replaceMcpToolOutputsForServer(...)`, `unregisterMcpTool(...)` — the manager never registered
  tools except through replace. `ToolRegistry implements McpToolSink`.
- Verified by the leader: `-Pquick` 1352/0/0/0 (same as baseline); `ToolRegistryTest`, `McpServerManagerTest` and the
  five screen-truth tests green; serialized `new ToolRegistry().getToolDefinitions()` byte-identical to baseline;
  string-literal multiset of `src/main` unchanged; every removed non-import line reappears with only the type rename.
- Left for later on purpose: `ToolExecutionResult.imageParts()` still returns `LlmClient.ContentPart` (ticket 02);
  render still takes `ToolExecutionResult` (ticket 03). `McpToolSink.getAuditLog()` and the old method name
  `replaceMcpToolOutputsForServer` (it replaces tools, not outputs) are inherited v1 shape — ticket 13 (MCP
  capability) should reshape the sink rather than carry them into v2. `MutatingToolsAgreementTest` failure messages
  still say `MUTATING_TOOLS` (literals were frozen for this ticket).
- Same-package leaves never needed an import of `ToolRegistry`, so the grep acceptance is weak for them; a non-import
  grep finds only prose (`{@code ToolRegistry.doExecuteTool}`) and the method name
  `McpResultPersister.relativePathForToolRegistry()`. `ToolEnv` / `ToolBatchExecutor` keep javadoc `{@link
  ToolRegistry}` — resolvable today, must go when v1 is deleted (ticket 29).
