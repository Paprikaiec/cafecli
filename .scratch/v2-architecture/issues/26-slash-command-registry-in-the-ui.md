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
