# 26: Slash command registry in the UI

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` session built-ins; `ui` completion) · ADR 0020 D6
**Blocked by:** 24
**Status:** ready-for-agent

**What to build:** In v2 interactive mode, slash commands complete and dispatch from the harness command registry, so
every command a capability registers shows up without touching the UI; unknown commands are rejected and `/plan`
prints its retirement notice, as in v1.

- Add the slash-command registration type to the SPI if no earlier ticket has. Session-level built-ins (`/clear`,
  `/exit`, help, and the other session commands v1 has) become a capability.
- The completer and dispatcher read the registry. Capabilities register their own commands in their own tickets;
  this ticket doesn't wait for them.
- Unknown `/xxx` is rejected at the CLI with v1's message, never passed to the model; `/plan` prints the same
  retirement notice as v1.

- [ ] The completer lists exactly the registered commands, including one registered by a test capability.
- [ ] An unknown command gets v1's "unknown command" message and no model request is made.
- [ ] `/plan` output is byte-identical to v1's.
- [ ] `CafeCliCompleterTest`'s behavior, re-expressed against the registry, passes.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-22 (grammar checkpoint from ticket 14 review).** Preserve the v1 command parser's whole accepted grammar, including malformed / missing / extra arguments and separator handling, as well as registered command names. Ticket14 pins `/skill` subcommand arity; the shared session dispatcher currently splits the initial command on arbitrary whitespace, whereas v1 uses some exact whole-command and literal-space prefixes. Audit the composed UI parser/dispatch path against CliCommandParser, including `/skill`, `/mcp`, `/memory` and case/whitespace variants. Unknown input must retain v1 rejection and must not execute a command or reach the agent. Do not silently widen syntax as a parity exception.

**2026-10-22 (ticket 15 integration checkpoint).** Browser capability registers `/browser` with the extracted
`browser/BrowserCommands` leaf, preserving v1 subcommand formatting and behavior. Its shared-mode `/tabs` must keep
using `AgentSession.invokeCommandTool` so hooks, permissions, browser guards and owned audit remain in force.
Dispatch reserves the session token under lock and runs the handler outside it; preserve cancellation, close and
awaitIdle coverage and the owner-only exception allowing `/clear`. Manager restart I/O keeps its existing bounded
behavior; token cancellation does not promise to abort every manager operation immediately.


**2026-10-24 (ticket24 UI integration checkpoint).** The temporary REPL handles only exit/plan/unknown locally;
replace these branches with registry-driven behavior and exact v1 text/grammar here. Preserve v1's initial
`submitted.trim()` normalization before command classification and ordinary preparation, including leading/trailing
whitespace, not only each slash subcommand's own arity. Current prompt rendering strips for display and session run
grouping trims final combined input; neither proves the UPS payload or busy steering saw v1's normalized input.
The event adapter now consumes SessionStatus model/window/context snapshots, so model/clear commands must update the
same facade state; do not introduce another UI model cache or touch engine internals.
