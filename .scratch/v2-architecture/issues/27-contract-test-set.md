# 27: Contract test set

**Parent:** `.scratch/v2-architecture/PRD.md` ("Contracts that must survive"; switch-over criterion 1) · ADR 0020
**Blocked by:** 13, 14, 15, 16, 18, 19, 20, 21, 22, 25, 26
**Status:** ready-for-agent

**What to build:** One command that proves v2 keeps every contract in the PRD — the evidence for switch-over criterion
1.

- A named set (a Maven profile or a JUnit tag) with at least one test per item under "Contracts that must survive",
  bound to v2 entry points. Most already exist from earlier tickets; fill the gaps and make the set explicit.
- A table in this ticket's Comments mapping each contract to its test(s).
- Check that every slash command v1 accepts is registered in v2 (except the removed `serve --http`).
- Don't count the known `NO_COLOR` red (AGENTS.md) or try to fix it here.

- [ ] The contract set runs with one command and is green against v2.
- [ ] The table is complete: no contract without a test.
- [ ] Every v1 slash command is reachable in v2.
- [ ] `mvn test -Pquick` is green.

## Comments

**2026-10-21 (input parity correction found during ticket 13).** Include the actual composed submission path from ticket23: MCP resource expansion → local `@path` expansion → UserPromptSubmit, for ordinary and busy/steering input. The ticket10 handoff incorrectly claimed v1 steering bypassed UPS; `cli/InteractiveRepl` prepares before `session.steer`. Current v2's bypass and missing local expansion must be resolved before this contract set is green, not added to the parity whitelist. Test blocked busy input and run-completion races as well as the normal expansion order.
