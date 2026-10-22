# 14: Skills and LSP capabilities

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` context placement) · ADR 0020 D4, D6
**Blocked by:** 07, 09, 11
**Status:** done

**What to build:** `load_skill` works as in v1 — the skill's body reaches the model ahead of the next user input — and
LSP diagnostics raised by a write reach the model at the next iteration.

- Skills: the skill registry (leaf), `load_skill`, the skill index in the system prompt (rebuilt on skill-set
  change), the skill commands. Skill bodies keep **today's** position, ported as a run-start log writer with its own
  kind. The glossary's Skill Instruction Injection position is out of scope.
- LSP: diagnostics produced after writes go into the boundary inbox as an LSP-diagnostics kind.

- [x] Seam 1: after `load_skill`, the body appears ahead of the next user input in the recorded request, and not
      before it.
- [x] Seam 1: a diagnostic raised by a write appears in the next iteration's request as a tagged entry.
- [x] The system prompt changes only when the skill set changes.
- [x] Existing skill and LSP leaf tests pass unchanged.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04b review).** Core's run-start seam is `AgentCallbacks.onRunStart(input, token) →
List<CustomEntry>`, appended **after** the user entry. The contract is that skill bodies reach the model **ahead of**
the next user input (v1 prepends them to the user message text, `Agent.prependSkillBodies`). So skill bodies are not
a plain run-start entry: either the harness builds the `UserMessage` with the bodies prepended before calling
`Agent.run` (v1-identical bytes), or a skill-body custom kind is merged in front of the user text by the harness's
`convertToLlm`. Pick one and pin the bytes against v1.

**2026-10-21 (from ticket 11 review).** Ticket 11 supplies cached prompt invalidation (AgentScope.systemPromptInvalidator / AgentSession.rebuildSystemPrompt), session commands, and fresh-agent /clear with onClear callbacks. Skill/MCP prompt contributions are not implemented yet. Memory hints read AgentScope.messages() via the registry, so visible custom LSP/skill entries should declare their model presentation and hidden entries remain excluded.

**2026-10-22 (from ticket 13 review).** AgentScope.promptContext now contributes static sections to the cached prompt; use it for the skill index rather than introducing another prompt builder. MCP's external context already uses this seam. AgentScope.onClose releases per-agent resources on clear/reap and failed attachment; session ownership remains SessionRegistrar.onClose. Preserve source-range normalization before ordinary projections, and keep skill-body placement byte-compatible with v1. Skill commands currently live in the v1 CLI: make any required leaf behavior reusable through a behavior-preserving move/adapter, without importing CLI into harness or copying its formatting algorithm.


**2026-10-22 (leader acceptance).** Skills reuse the registry, builtin extraction, state file, index formatter, SessionTools load_skill leaf and per-agent SkillContextBuffer. The shared SkillCommands leaf holds the old formatting implementation; v1 keeps a delegating CLI shim, with unchanged leaf assertions. SkillBody is a run-start custom entry; MessageKind.userPrefix merges its exact drained prefix into the actual preceding UserEntry only in pure presentation. Body injection waits for the next real user run, does not attach to recall/steering messages, preserves other user content, and never rewrites source entries. Source-first summary normalization and append-only source bytes are covered together. Per-agent buffers and index observers are released on clear/close, omitted capabilities contribute nothing, and actual-composition message kinds round-trip.

Skill commands preserve reviewed v1 subcommand arity and original formatting. Index invalidation uses each agent's actual immutable indexed set, not two live reads around a command: external skills.json changes followed by reload update old prompts even if a newer child already indexed the changed state. Unchanged reload and repeated state commands keep the cache. Builtin extraction failure keeps the exact v1 status note through an injected sink and still discovers user/project skills. Spec review found both malformed-command execution and stale external-state caching; the same implementer observed red-to-green regressions for both and both independent review axes rereviewed clean.

LSP reuses the shared parser with per-agent forked pending state and explicit captured enabled/max settings. The existing dynamic v1 constructor/forks and formatter entry point remain intact. After a tool batch, only typed OK write/edit results with normal-return execution provenance and a structured diff path trigger diagnosis; unique final paths are re-resolved through PathGuard before reading. This does not trust a preflight-supplied diff-shaped result. Reports enter the common inbox before the next boundary, reaching the immediately next model request; disabled settings and clear/agent isolation are covered. V1's leaf hook runs after the mutation lock is released; final-per-file post-batch diagnosis avoids new workspace injection or agent-ID maps. Core and workspace implementations did not change. Combined LSP/Hook/steering presentation ordering remains an explicit ticket27 parity checkpoint; parser separator handling belongs to26 and prefix/image composition to16. No new parity exception was authorized.

Implementer broad named suites: **448/0/0/0**. Independent leader quick: **1706/0/0/0**; independent named core/harness/app/architecture/skills/LSP/registry/environment/child-registry/status: **448/0/0/0**, including architecture8. Existing skill/LSP leaf assertions were unchanged. Clean package passed. A standalone checker compiled against the packaged jar used actual production Composition to verify project skill list/show/on/off and scoped state bytes, delayed exact next-user prefix, tagged post-write diagnostics, unchanged prior source log, and clear dropping pending bodies/diagnostics. Actual packaged CLI passed tool registration, exact written bytes, stdout/stderr and durable write audit; load_skill correctly has no mutation audit. Packaged MCP/hook/session-ID/memory/budget/guard regressions also passed. No real keys/external services, render changes, or real-terminal claim. Local commit only; no push.
