# 26: Slash command registry in the UI

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` session built-ins; `ui` completion) · ADR 0020 D6
**Blocked by:** 24
**Status:** done

**What to build:** In v2 interactive mode, slash commands complete and dispatch from the harness command registry, so
every command a capability registers shows up without touching the UI; unknown commands are rejected and `/plan`
prints its retirement notice, as in v1.

- Add the slash-command registration type to the SPI if no earlier ticket has. Session-level built-ins (`/clear`,
  `/exit`, help, and the other session commands v1 has) become a capability.
- The completer and dispatcher read the registry. Capabilities register their own commands in their own tickets;
  this ticket doesn't wait for them.
- Unknown `/xxx` is rejected at the CLI with v1's message, never passed to the model; `/plan` prints the same
  retirement notice as v1.

- [x] The completer lists exactly the registered commands, including one registered by a test capability.
- [x] An unknown command gets v1's "unknown command" message and no model request is made.
- [x] `/plan` output is byte-identical to v1's.
- [x] `CafeCliCompleterTest`'s behavior, re-expressed against the registry, passes.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-25 (implementation checkpoint; acceptance pending).** The same ticket26 implementer owns source/tests
and Maven until explicit release. Registry completion and typed presentation now cover capability commands and
session built-ins; ordinary command handlers execute asynchronously under session admission so the resident reader
can answer auxiliary approvals. A UI FIFO preserves pasted ordinary submissions through command completion, including
queued exit and modal effects. Focused tests cover dynamic command completion, unknown/malformed rejection, source
trimming, tool-command approval/cancel, FIFO/draft/modal behavior and provider configuration followed by model switch.
Remaining project-scope and interrupt-race checks, final source freeze, both independent review axes and leader
verification are still required. No checklist item is accepted from this checkpoint alone.

**2026-10-25 (freeze1 independent reviews; corrections open).** Leader verified all 59 source hashes plus the
focused log and 66 XML hashes: 695 tests, zero failures/errors/skips. Standards found one P2: project replacement
closed the old snapshot service without draining its accepted post-turn writer. Spec found two P2s: newly registered
`/context`, `/policy`, `/hooks` and `/audit` refused observations while MAIN was busy, and `/cancel` newly cancelled
work where actual v1 always prints its idle notice. The same implementer owns all three corrections and public-seam
regressions; both original reviewers will inspect freeze2. Serial ownership for owner-mutating commands remains in
force. No new parity exception is requested or assumed. Final full suite, leader checks and packaged acceptance
remain pending; Maven is still exclusively owned by the implementer.

**2026-10-25 (freeze2 final independent reviews clean).** The same Standards reviewer rechecked the snapshot
correction and all 14 changed/new paths; the same Spec reviewer rechecked both behavior corrections and the rest of
the delta. Standards: zero remaining hard violations or actionable smells. Spec: zero remaining findings. Old
snapshot writers drain before replacement; drain/factory failures keep the old service open and owned, interrupted
callers retain their flag, and custom services keep their root. Busy observations use an immutable owner-captured
context description; `/cancel` preserves the actual v1 no-op. A further captured-root audit reproduced two image
completion regressions (duplicate candidates and a Skills-only host-directory lookup); image completion now takes
priority once at the registry boundary and Skills receives the captured completion root. Public session/reader
regressions cover these corrections, including exact POST_TURN blob bytes. Leader and Spec reviewer separately
verified all 61 freeze2 source hashes, the focused log and 67 XML hashes: 712/0/0/0.

Evidence: `/tmp/cafecli-ticket26/source-freeze-2.json`, `freeze2-final-focused-audit.json`,
`freeze2-final-focused-reports/`, and `evidence-classification.json` in the same directory. Genuine snapshot,
busy-observer and image-completion reds are archived; the earlier wake-race pipe-writer failure is explicitly
fixture-invalid. Final full suite, independent leader checks, stable-jar scenarios and the local commit still remain.

**2026-10-25 (leader acceptance).** Both independent review axes are clean on unchanged freeze2. The implementer's
single final full run passed **2251/0/0/0**, 259 fresh XML reports. Leader independently verified the exact command,
61 before/after source hashes, log hash, all XML hashes, report times within the run, Maven class set and totals
against `final-full-audit.json` / `final-full-reports/` under `/tmp/cafecli-ticket26/`.

Independent leader checks (tests/failures/errors/skips): quick **2147/0/0/0** (254 suites), named **1363/0/0/0**
(152), rendering smoke **457/0/0/0** (45), screen truth plus isolation **89/0/0/0** (6), and clean package passed.
Logs are `/tmp/cafecli-ticket26-leader-{quick,named,smoke,screen,package}.log`. Fresh jar SHA-256:
`6c62071a825f69e92189c1548592dc95ac6fd87581e85bdd6cf1c315a7b03dde`.

Actual packaged checks passed: dynamic real-reader Tab completion, captured-root image candidates, FIFO command
output/exit, exact unknown rejection and no model history; 384 immutable original-v1 parser/alias/malformed/case/
whitespace comparisons and identical retirement-notice bytes; real native-PTY command/config dispatch; a held local
Step HTTP call with all four busy observations and unchanged `/cancel`, followed by a provider configuration/model
switch using the new endpoint/key while preserving history; and project replacement waiting for old snapshot writes
with exact POST_TURN blob bytes and preserved caller interruption. Evidence scripts/logs are
`/tmp/cafecli26-{jar,entry,grammar,snapshot-switch}-check.py` / `.log`. All 18 earlier packaged fixtures passed the
same stable jar (`/tmp/cafecli26-prior-regressions.log`); native24 entry and the actual v1/v2 startup-trust comparison
also passed (`/tmp/cafecli26-24-entry.log`, `/tmp/cafecli26-25-trust.log`).

**Native boundary retained, not claimed fixed.** The first native25 entry replay failed in `stty` during Ctrl+C
(`/tmp/cafecli26-25-entry.log`); an isolated full replay passed (`/tmp/cafecli26-25-entry-final.log`). Balanced
standalone runs passed 5/5 for accepted25 and26. Under four-worker load, accepted25 had 15 passes, two `stty` exits
and three spawn-helper SIGINT failures;26 had 13 passes, five spawn-helper SIGINT failures, one interrupted terminal
size read during cleanup and one raw pending-prompt wait failure. Exact classified artifacts are
`/tmp/cafecli26-native-cancel-stress/classified-results.json`; the immutable25 reference is
`/tmp/cafecli-accepted25-reference.jar`. Same-context read-only diagnosis confirmed the shared setup/cleanup paths
and no reachable26 FIFO wake in this scenario. This establishes a pre-existing contract gap, not a26-specific
regression or a fix. Ticket27 must bind and resolve it; no new parity waiver, manual acceptance or switch-over is
claimed here. All reviewed source hashes and the packaged jar remained unchanged through these probes.

Final staging found trailing spaces on two blank lines in the newly added CodeCommands.java. The same implementer
removed only those 16 spaces; the same Standards reviewer verified all 61 freeze3 hashes and identical stripped
content/line count. `source-freeze-3.json` and `source-freeze-3-whitespace-audit.json` record that sole formatting
delta from the tested freeze2; there was no behavior change or additional Maven run.

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


**2026-10-24 (ticket25 contract handoff).** Ticket25 re-expresses the applicable reader/key/signal/cancellation
contracts against v2; its explicit mapping defers the legacy command-transcript, unknown-command and command exit
outcome cases here. Rebind those remaining cases in the actual UI/registry path, not just parser unit tests. Ctrl+Y
already calls the registered approval-mode facade; /hitl must use that same state and PermissionCapability grant
store, including when main work is busy, without a second UI policy cache.
