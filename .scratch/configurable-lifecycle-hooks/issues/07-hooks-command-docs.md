# 07: /hooks command + consolidated docs sweep

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/configurable-lifecycle-hooks/PRD.md` (ADR 0012)

## What to build

The inspection surface and the closing docs pass.

`/hooks` lists every loaded hook: source (builtin / user / project), event, matcher, trust state (for project hooks), enabled/disabled, and recent decisions (fires/blocks this session). Unknown subcommands error at the CLI layer per the repo convention (never fall through to the agent). Command entry follows the full linkage rule: parser, main loop, completer, tests, docs.

Consolidated docs sweep: AGENTS.md (new subsystem section: events, outcomes, tenants, config, trust, `-D` toggles), README, ROADMAP Phase 23 status (delivered scope vs deferred wave-2 items), and a check that per-slice doc edits from 01–06 are consistent.

## Acceptance criteria

- [ ] `/hooks` shows source/event/matcher/trust/enabled and session fire/block counts for builtin and shell tenants
- [ ] Unknown `/hooks` subcommand → CLI-level "unknown command" error
- [ ] Completer offers `/hooks` and its subcommands from the shared completer exit
- [ ] Seam 7 coverage (`CliCommandParserTest` precedent) plus main-loop linkage tests
- [ ] Output goes through the renderer stream (no bare stdout in the interactive path)
- [ ] AGENTS.md / README / ROADMAP updated and mutually consistent; wave-2 deferrals (updatedInput/updatedToolOutput, async, agent-type hooks, SubagentStart/Stop) listed as not-delivered

## Blocked by

- 04 (`04-project-hook-trust.md`) — trust state display
- 05 (`05-allow-ask-hitl-integration.md`) — decision kinds shown in listing
