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

**2026-10-22 (combined context ordering checkpoint from ticket 14).** Verify combined LSP diagnostics, PostToolUse hook context and busy steering in one recorded request against v1's actual boundary order (LSP → Hook → Steering, nearest the next request). The new common inbox preserves arrival order, which is not automatically the same ordering. Test the composed capabilities, reconcile any difference in harness presentation without mutating source history or teaching core product kinds, and do not silently whitelist it. Keep each diagnostic in the first eligible next iteration; source-range compaction must still run before ordinary view ordering.


**2026-10-23 (model-window contract audit checkpoint).** V1's Agent.buildExternalContext gates the MCP resource
index through ContextProfile.mcpResourceIndexEnabled(); current v2 McpCapability.promptContext calls promptIndex()
unconditionally. Existing default provider windows all exceed the threshold, so ordinary provider/switch fixtures
cannot distinguish the paths. When filling this set's contract gaps, cover a configured small-window override and
model switching with actual MCP resources: emitted prompt content must agree with the model-derived index fact.
Reuse the neutral ContextProfile rule rather than duplicating its threshold, keep prompt invalidation observational
for trace, and do not whitelist a new request difference. This audit requires a composed regression before correction.


**2026-10-24 (interactive configuration-surface audit checkpoint).** When binding the CLI/config surface, compare
actual entry history/completion setup as well as slash names: v1 Main configures its history path/options and uses
CafeCliHistory/CafeCliHighlighter, while the current v2 Surface still constructs DefaultHistory and passes null
highlighter/completer (registry completion is26). Verify the observable history storage/filtering/editing behavior
and captured-home path against v1; don't assume JLine defaults prove parity or silently whitelist a difference.
Any needed leaf prefactor must remain behavior-preserving and keep process reads at the system entry.

**2026-10-25 (ticket26 integration checkpoint).** Include the actual registry/reader command regressions, not only
the extracted parser: dynamic completion, all legacy grammar forms, unknown/exit/source echo, async auxiliary
approval, FIFO/draft/modal wake behavior, and captured-directory image completion. `/context`, `/policy`, `/hooks`
and `/audit` use independent observation while MAIN runs; context descriptions are immutable owner captures carried
with SessionStatus. Actual v1 `/cancel` is a no-op with the constant idle notice, including while busy; cancellation
contracts use the existing key/signal/session cancellation route. Keep those two facts separate in the table.
Project changes also drain accepted old-project snapshot writes before replacing services; gate/commit-byte and
failure-retention regressions are in SnapshotSessionTest. Do not replace those with a successful new-root snapshot
as an indirect criterion. Ticket26 evidence and exact acceptance state belong in its issue.

**2026-10-25 (native Ctrl+C diagnostic checkpoint).** Ticket26 packaged acceptance exposed a shared baseline
terminal gap beyond the pipe-driven rigs: Ctrl+C during JLine's ExecPty raw-mode/cleanup window can interrupt or
kill an `stty` / JDK spawn-helper subprocess, producing an uncaught terminal IOError. The same `stty` failure was
reproduced twice against the immutable accepted25 jar, so this is not established as a new26 regression. Balanced
standalone runs passed 5/5 per version; a loaded 20/20 comparison had additional failures in both versions, with
exact classifications/artifacts under `/tmp/cafecli26-native-cancel-stress/` and the script/log beside it. The
original26 failure is `/tmp/cafecli26-25-entry.log`; accepted25 reference is `/tmp/cafecli-accepted25-reference.jar`.
Bind a meaningful real input-boundary regression and resolve this cancellation-contract gap before calling the
contract set complete. A passing pipe rig or one native rerun cannot establish that it is fixed. Preserve unrelated
terminal I/O errors, single-reader ownership and actual cancellation/draft behavior; no new parity waiver is implied.
The same loaded run also caught an alive26 process with a visible pending approval card but a stale `steer` right
prompt (`v26-16.log`); verify the actual captured input readiness/refresh contract, rather than depending on a raw
substring wait or accepting an indefinitely stale prompt. Exact classifications are in `classified-results.json`.
