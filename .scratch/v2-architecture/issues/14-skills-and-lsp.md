# 14: Skills and LSP capabilities

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` context placement) · ADR 0020 D4, D6
**Blocked by:** 07, 09, 11
**Status:** ready-for-agent

**What to build:** `load_skill` works as in v1 — the skill's body reaches the model ahead of the next user input — and
LSP diagnostics raised by a write reach the model at the next iteration.

- Skills: the skill registry (leaf), `load_skill`, the skill index in the system prompt (rebuilt on skill-set
  change), the skill commands. Skill bodies keep **today's** position, ported as a run-start log writer with its own
  kind. The glossary's Skill Instruction Injection position is out of scope.
- LSP: diagnostics produced after writes go into the boundary inbox as an LSP-diagnostics kind.

- [ ] Seam 1: after `load_skill`, the body appears ahead of the next user input in the recorded request, and not
      before it.
- [ ] Seam 1: a diagnostic raised by a write appears in the next iteration's request as a tagged entry.
- [ ] The system prompt changes only when the skill set changes.
- [ ] Existing skill and LSP leaf tests pass unchanged.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04b review).** Core's run-start seam is `AgentCallbacks.onRunStart(input, token) →
List<CustomEntry>`, appended **after** the user entry. The contract is that skill bodies reach the model **ahead of**
the next user input (v1 prepends them to the user message text, `Agent.prependSkillBodies`). So skill bodies are not
a plain run-start entry: either the harness builds the `UserMessage` with the bodies prepended before calling
`Agent.run` (v1-identical bytes), or a skill-body custom kind is merged in front of the user text by the harness's
`convertToLlm`. Pick one and pin the bytes against v1.

**2026-10-21 (from ticket 11 review).** Ticket 11 supplies cached prompt invalidation (AgentScope.systemPromptInvalidator / AgentSession.rebuildSystemPrompt), session commands, and fresh-agent /clear with onClear callbacks. Skill/MCP prompt contributions are not implemented yet. Memory hints read AgentScope.messages() via the registry, so visible custom LSP/skill entries should declare their model presentation and hidden entries remain excluded.
