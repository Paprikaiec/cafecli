# 28: v1 / v2 parity harness

**Parent:** `.scratch/v2-architecture/PRD.md` (seam 4; "Intentional behavior changes"; switch-over criterion 2)
**Blocked by:** 13, 14, 15, 16, 18, 19, 20, 21, 22
**Status:** done

**What to build:** Evidence that v2 behaves like v1 except where the PRD says it shouldn't: the same scenarios run
through v1 headless and v2 headless, and every difference is explained by a numbered intentional change. Temporary —
deleted with v1.

- Scenario scripts readable by both a v1 stub LLM client and the v2 fake provider. Cover at least: plain answer;
  read → edit → run tests; approval denied; PreToolUse BLOCK; Finish Gate block-then-pass; compaction; background task
  with notification; cancel mid-run; MCP tool call; skill load.
- Compare final answer, files on disk, and trace records (normalized for ids and timing).
- Each difference must match an entry in "Intentional behavior changes"; anything else fails. Adding an entry needs
  the maintainer's sign-off and an update to the PRD and ADR 0020.
- Feed both traces through the eval normalizer; summaries must match unless an intentional change explains it.

- [x] Every scenario passes, with each remaining difference labeled by its intentional-change number.
- [x] Normalizer summaries match for every scenario not covered by an intentional change.
- [x] `mvn test -Pquick` is green.

## Comments

**2026-10-20 (from ticket 04a review).** The fake script format (`ai/fake/FakeScript`) was kept v1-expressible: text,
thinking (→ v1 `reasoningContent`), tool calls (id / name / raw arguments), usage (input / output / cached), and
errors. Known limits for this harness: (1) one ordered list, no dispatch by request — two agents sharing one fake
(background tasks) get answers in nondeterministic order; script such scenarios per agent or with a request-keyed
responder; (2) the fake never blocks, so "cancel mid-run" races the stream finishing — add a hold-until-cancelled step
if a parity scenario needs it; (3) on v1 all four error kinds collapse into one `IOException`, so compare outcomes, not
error kinds; (4) the loader rejects blank tool-call ids/names (v1 silently drops them).


**2026-10-23 (ticket18 approved parity exception10).** The maintainer approved next-eligible-iteration delivery of
background completions while the parent is busy; actual v1 queues them for a new run. Cover this scenario and label
its timing/derived iteration-count differences with PRD exception10. Do not use it to excuse unrelated notification
text, source provenance, cancellation or headless behavior.


**2026-10-23 (ticket19 snapshot contract, exception11).** Actual v1 HeadlessRunner bypasses the interactive
SnapshotService.runTurn wrapper. Ticket19 explicitly anchors every v2 main run, including headless, while children
share restore access without independent anchors. Compare headless snapshot/revert behavior explicitly and label
that requested contract correction with PRD exception11; bare/gitfile preservation and real-path containment remain
absolute unchanged leaf contracts.


**2026-10-23 (ticket20 trace observability, exception12).** V1 drops final-answer reasoning and leaves result token
totals zero/stale except at natural successful return. V2's compatible trace keeps retained reasoning and totals
completed MAIN responses, excluding partial ERROR/ABORTED, child and internal compaction calls. Label only those
reasoning/metric differences with PRD exception12; record fields, main assistant counts, prepared user content and
finalized tool-result publication still require compatibility.

**2026-10-23 (ticket21 model command checkpoint).** V2's `/model` context summary omits only the retired
short-term-memory budget field under exception6; window, compression threshold, MCP resource-index and prompt-cache
facts remain present. Provider selection, configured endpoint quirks, persisted choices and captured default-child
models/routes still require compatibility, including startup alias fallback versus later canonical selection.

**2026-10-25 (ticket26 command audit).** Actual v1 `/cancel` always prints its idle notice and does not cancel a
running turn; ticket26 preserves that behavior. The mid-run cancellation scenario must use the real session/token
or key/signal cancellation route, then apply exception9 only to the approved interruption timing difference.
Explicit `/config provider` followed by `/model` can select a newly configured transport generation; existing child
routes retain their captured endpoint/key/history. These command and route behaviors have no new parity waiver.

**2026-10-26 (implementation started from accepted27).** Ticket27 is committed locally as
`9257f8033fe7860223a36c42c67aba68f952e200`. A fresh implementer owns source/tests/resources/build configuration and
exclusive Maven; root owns documentation, two independent review axes, independent validation and the local commit.
The harness must keep its scenarios and MCP/fake fixtures in the repository, execute actual v1 and v2 headless
paths, and invoke the actual eval normalizer. Every difference needs a narrowly scoped existing PRD number;
unexplained answer/file/trace/summary differences fail. Per-agent scripts and a held cancellation step address the
known fake limitations above. No new parity exception, default switch, v1 deletion or AGENTS change is authorized.
Stop before29 and human terminal acceptance.

**2026-10-26 (unapproved headless answer gap; correction authorized).** The shared retained-reasoning script
reproduced a final-answer difference: actual v1 HeadlessRunner includes `🧠 思考过程:` plus completed iteration
reasoning, while v2 RunOutcomeText returned only final text. Evidence: `/tmp/cafecli-ticket28/reasoning-gap.log`
(12 tests / one failure). Root independently verified v1 Agent.run's per-run accumulation and natural-completion
formatter. PRD exception12 permits trace reasoning differences, not removal from the returned answer; no new waiver
is approved. The same implementer may correct headless presentation while preserving typed outcomes, assistant text,
source/model history, compatible trace and interactive event rendering. Tool and Stop-blocked iterations, run reset,
whitespace/reasoning-only results and exclusion on cancel/failure/budget need explicit regressions. Production changes
await the implementer's concrete seam/API plan; the initial ten required scenarios already pass with scoped5/9
exceptions, and busy-notification/snapshot coverage is still pending.

**2026-10-26 (presentation seam approved).** Root approved a pure harness HeadlessRunPresenter subscribed to
MAIN RunStart/MessageEnd/RunEnd, wired only into headless Cli presentation and the parity runner. It resets per run,
collects only completed MAIN thinking, concatenates blocks within each message before trimming, and joins iteration
reasoning with blank lines. Natural completion uses v1's heading and trimmed answer; nonnatural outcomes delegate to
the unchanged outcome formatter. No projection/I/O occurs in the synchronous subscriber. Existing Cli.report and
RunOutcomeText APIs, child result presentation, core outcomes, assistant text, UI events and trace remain unchanged.
Implementation and independent reviews are pending.

**2026-10-26 (freeze1/2 reviews; corrections in progress).** The focused freeze1 set passed62 tests and the real
normalizer's existing suite passed8. Root independently verified all archived evidence hashes, the seven focused
XML totals and all32 actual normalizer outputs across16 paired scenarios. Contracts initially had724 passes and one
old stdout expectation; the approved stdout-only TraceCompositionTest correction preserves all trace/content/usage
assertions. Freeze2 affected11 and contracts725 /72 suites then passed, with all796 source hashes unchanged during
validation. Full and quick acceptance remain pending.

Standards found no documented breach or actionable heuristic issue. Spec found two P2 gaps: raw Unicode whitespace
must follow v1's `isBlank()` before trim and its final transcript trim, including control-only/empty transcript cases;
and busy-notification needs independent per-arm file-byte snapshots/reseed, not just a note-file check after both
runs. The same implementer is adding paired reds and correcting only those seams. The MCP config omission is also
being narrowed to its explicitly arranged fixture, whose bytes must remain unchanged; all other scenarios must see
unexpected config-file creation/change/deletion. No new production behavior or parity waiver is approved. Both
original reviewers will recheck the final freeze before the single final full run.

**2026-10-26 (freeze3 review closure and scenario map).** Both original reviewers are clean after the corrections.
The frozen source has804 files /41 changed implementation, test, resource and POM paths. Focused77 tests /9 suites
passed; the explicit contract profile passed727 /72 suites. Quick and the single final full suite are still pending.
Root independently re-ran the actual normalizer on all44 saved traces, verified all22 paired answer/file manifests
and154 evidence hashes, and checked the focused log/XML class sets and totals. Focused archived XML mtimes reflect
copy time, so they are not claimed as original report timestamps. Final profile audits record source before/after
hashes and original report metadata. No manual terminal acceptance or switch-over has occurred.

The one-command parity entry is `env -u NO_COLOR TERM=xterm mvn test -Dtest='com.cafecli.parity.*Test' -DskipTests=false`
on Java21 with Python3. Shared scripts live in `src/test/resources/parity/`; the actual v1/v2 runners, file observer,
strict comparator and actual eval bridge are in the matching test package. Each successful run retains paired raw
traces, real summaries, exact answers, Base64 file-byte manifests and exception labels in a unique
`target/parity-evidence/` directory. Archive it before `clean`; final acceptance uses versioned `/tmp` copies.

| Scenario | Exception | Remaining difference / strict observation |
| --- | --- | --- |
| plain-answer | none | Exact answer, files, records and real summary |
| read-edit-test | none | Real read/edit/command and test-result bytes |
| approval-denied | none | Rejected approval, no write |
| pre-tool-block | none | Real typed Hook BLOCK feedback, no write |
| finish-gate | none | Stop block then real verification and completion |
| skill-load | none | Actual skill load and next-input body placement |
| headless-background | none | Synchronous child; no event loop or push |
| headless-parallel-children | none | Request-keyed independent children; only nondeterministic task IDs normalized |
| mcp-tool | none | Real local MCP HTTP; arranged config bytes checked unchanged per arm |
| project-config-file | none | Legitimate .cafecli/mcp.json bytes remain observable |
| unicode-blank-reasoning | none | Blank reasoning gives no heading |
| compaction | 5 | Exact v1 acknowledgement and one derived turn; summary/content stay strict |
| cancel-mid-run | 9 | Real held request stops earlier; cancelled outcome and next request identical |
| busy-notification | 10 | Exact one extra v1 completion/derived turn before the same notification; provenance/files strict |
| headless-snapshot | 11 | MAIN anchor lets one file revert; exact gitfile bytes preserved, no child anchor |
| retained-reasoning | 12 | Final trace retains exact reasoning; returned answer matches v1 |
| control-only-reasoning | 12 | Exact retained trace reasoning only; empty normalized transcript has no heading |
| mixed-controls-reasoning | 12 | Final trace retention only; output separator mechanics match v1 |
| mixed-unicode-blank-reasoning | 12 | Final trace retention only; blank intermediate reasoning stays omitted |
| nonblank-unicode-reasoning | 12 | Final trace retention only; nonblank Unicode text is preserved |
| budget-completed-usage | 12 | Actual zero v1 totals versus exactly3/2/1 completed MAIN usage |
| failed-completed-usage | 12 | Same metric correction; partial ERROR reasoning/text/usage excluded |

Every case rejects content/field/record/summary mutations. Whenever compatible traces differ, the same difference
also fails under an unapproved scenario name. Complete file observations include dotfiles; generated MCP endpoint omission is
limited to its validated fixture-owned bytes. Addition/deletion/changed-byte and ordinary/owned config mutations
have independent negative tests. Temporary seam4 runners/fixtures retire with v1 at29; HeadlessRunPresenter and its
contract-profile session/CLI/fake-hold regressions are durable.

**2026-10-26 (independent defaults probe; maintainer decision pending).** Root independently passed parity77,
contracts727, quick2213, named1429 and clean package after the implementer's full2317. All804 frozen source hashes
remain unchanged. Candidate jar SHA-256 is `6278129de81ea948da824943197a399d64d038fef437e39d97a5902394bbf472`.
These green results establish the reviewed matched-capability seam, not default CLI equivalence.

A supplemental actual-jar probe then failed strict default system-trace comparison: v2's builtin skill index is
present while v1 Main's bare headless registry never installs a skill registry. Further isolated local-HTTP probes
confirmed v1 default load_skill/spawn_agent/MCP calls are unavailable/unknown, while v2 loads the skill, runs one
synchronous child and calls the configured MCP fixture. Browser connector handling differs too; no real browser was
contacted. LSP works in both and must not be removed as an allegedly missing v1 capability. Evidence:
`/tmp/cafecli28-entry-parity-check.log`, `/tmp/cafecli28-default-capability-audit.log` and
`/tmp/cafecli28-default-browser-audit.log`. Source audit: Main.runHeadlessCommand versus Composition.session.

With the builtin skill explicitly disabled in both fixture homes, the constrained actual-jar plain/read-edit-test/
retained-reasoning cases pass strict traces, file bytes, real summaries and exact v2 reasoning stdout:
`/tmp/cafecli28-entry-parity-matched-check.log`. This does not erase or waive the failed defaults probe. The original
Spec reviewer confirmed exception8 does not cover activation of these extra default adapters. Earlier accepted
SkillsLspCompositionTest, SubAgentsCompositionTest and WebRagBrowserCompositionTest require the full embedded
headless composition, so removing those features globally would regress that contract.

Root prepared `/tmp/cafecli-ticket28/default-entry-decision.md` and requested the maintainer's choice: retain full
v2 default headless skill/MCP/task/browser activation with an explicitly approved narrow new PRD/ADR exception, or
restore the v1 CLI baseline while preserving full explicit sessions and interactive behavior. No new exception is
authorized yet; no production change was made for this discovery. Ticket28 stays in progress, uncommitted, and
switch-over criterion2 remains open. Ticket29 and all human-terminal/default-switch/deletion work remain unstarted.

**2026-10-26 (maintainer decision approved; default-entry coverage in progress).** The maintainer answered
"Keep v2’s full headless capabilities", accepting the recommended concrete decision above. PRD exception13 and
ADR0020 now record the narrowly scoped headless skill/MCP/task/browser bindings. This supersedes the pending-choice
checkpoint; no further approval is needed for that scope. LSP remains an unchanged v1 capability, headless remains
synchronous, and guards/trust/leaf semantics plus unrelated content/files/trace/summary fields stay strict. The
existing22 matched-capability cases and their clean freeze3 validation remain preserved. The same implementer now
owns Maven and adds actual production-default CLI subprocess coverage and scoped negative comparisons, using only
local HTTP/fake model/browser fixtures. No production capability is being removed. Both original reviewers will
review the new delta before final acceptance and the local commit. Ticket29 remains unstarted.

**2026-10-26 (approved13 actual-default slices pass; final freeze pending).** The same implementer has seven
actual Main subprocess scenarios passing: plain, builtin load, synchronous background-requested child, configured
MCP tool/resource index/mention, browser disconnect without Chrome, unchanged LSP and read/edit/shell verification.
Each actual default difference fails without13. Full MAIN wire bodies remain strict after literal context/result
patches, the exact configured MCP definitions and existing8 schema ordering; real eval summaries match exactly.
The tests capture actual stdout-derived answers, exact project files including MCP config, declared builtin cache,
MAIN/child/MCP wire data, and reject unrelated mutations. Browser ERROR/OK is independently observed from typed
results through the bare leaf and full composition, not inferred from text prefixes. No9222 or real browser/provider
probe is used. Production and the existing22 matched-capability source paths remain unchanged from freeze3.
Final focused freeze, the original two review axes and final validation are pending; this is not acceptance yet.

**2026-10-26 (accepted after approved13 coverage and independent verification).** Standards: clean. Spec: clean.
Both original reviewers verified the final freeze4:818 source/POM files,55 changed implementation/test/resource paths,
and360 evidence hashes. Its14 additions are tests/resources only; all previous804 files, including production,
POM and the existing22 scenarios, are byte-identical. Maintainer approval13 resolves the default-entry policy gap;
production retains the full headless profile. The original failed defaults probe remains as pre-approval evidence,
not a claimed product fix or a comparison erased by disabling skills.

The final harness covers29 scenarios: the22 matched-capability cases above plus these actual Main subprocess cases.
Each default case rejects its actual difference without13. All cases compare actual answers, complete project file
bytes and real eval summaries; default cases also compare MAIN wire bodies, declared cache contents, child/MCP
requests and literal context/result/schema oracles. Negative comparisons keep unrelated fields, files and metrics
strict. Exception8 is limited to tool ordering;13 permits only the declared default bindings.

| Actual default scenario | Approved differences / unchanged control |
| --- | --- |
| default-plain | Exact builtin skill index/cache13; tool order8 only |
| default-read-edit | Same13 index; real read/edit/shell-test bytes and results remain strict |
| default-skill | Exact formerly-uninitialized versus loaded-skill result and index/cache13 |
| default-mcp | Exact configured definitions/index/resource mention/result13; real local MCP requests |
| default-spawn | Exact unavailable versus synchronous child result13; child usage stays out of MAIN totals |
| default-browser | Exact connector result13; typed ERROR/OK observed directly, no Chrome/9222 probe |
| default-lsp | Same13 index; LSP diagnostics, source order and results remain strict |

Final implementer focused84 /10 suites, quick2220 /266 and full2324 /271 passed with zero failures/errors/skips.
Root independently passed focused84 /10, quick2220 /266 and clean package on the final source. Prior independent
contract727 /72 and named1429 /163 evidence remains valid because production/POM and the previous804 files are
unchanged. All818 before/after source hashes, original XML hashes/timestamps/class sets and log hashes were checked.
Evidence: `/tmp/cafecli-ticket28/final-handoff4.json`, `freeze4-validation/`, `leader-ex13-validation/` and
`leader-freeze4-{artifact-audit,verified-quick,verified-full}.json`. Root independently reproduced all58 actual
normalizer outputs,29 answer/file pairs and seven default trace/MAIN-wire pairs using the literal approved patches.

Fresh jar SHA-256: `bccb4d91be91cf7109b977ad79884d45ae61f7d469179e930b7c2100bc9b42f3`.
Root's independent actual-jar plain/read-edit-test/retained-reasoning checks pass with default capabilities enabled,
only the exact known builtin index under13 and retained final trace reasoning under12, exact file bytes, real eval
summaries and exact v2 reasoning stdout. Log: `/tmp/cafecli28-approved-entry-parity-check.log`; its fixture directory
retains all raw records and requests. No real provider, real browser or human-terminal acceptance was performed.

The local commit is restricted to the55 implementation paths plus PRD, ADR0020, README, agents-reference and this
issue. Unrelated scratch remains excluded; no push, AGENTS change, default switch or v1 deletion. Stop before29 and
its maintainer smoke/go. The earlier27 modal-notice visibility timeout remains documented and unresolved; these
passing offline runs do not establish a fix.
