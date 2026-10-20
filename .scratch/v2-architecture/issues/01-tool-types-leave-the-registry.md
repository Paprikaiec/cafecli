# 01: Tool definition and result types leave `ToolRegistry`

**Parent:** `.scratch/v2-architecture/PRD.md` (Migration — v1 freeze exception) · ADR 0020 D9
**Blocked by:** None (can start immediately)
**Status:** ready-for-agent

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

- [ ] No tool group, the approval policy, the MCP server manager, or the MCP result budget imports `ToolRegistry`.
- [ ] `mvn test -Pquick` is green; tests change only in imports and type names, never in assertions.
- [ ] The tool list the model receives is unchanged in content and order.
