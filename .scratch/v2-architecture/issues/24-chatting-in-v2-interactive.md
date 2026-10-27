# 24: Chatting in v2 interactive mode

**Parent:** `.scratch/v2-architecture/PRD.md` (`ui` layer; seam 3) · ADR 0020 D1 · ADR 0010, 0014, 0016, 0017
**Blocked by:** 03, 23
**Status:** done

**What to build:** `java -Dcafecli.v2=true -jar …` opens the interactive session and you can chat: answers stream,
thinking folds, tool blocks show their results, the status dock updates — looking exactly as in v1. Approvals are off
in this ticket (ticket 25).

- The v2 interactive entry under the flag, on the event loop from ticket 23.
- An event → render adapter onto the render-owned typed calls from ticket 03 (reasoning, answer, tool calls, tool
  results, code block, diff, command output). The render package is reused unchanged.
- The answer splitter (markdown and fenced code-block detection) and the thinking-panel handling move from v1's agent
  stream renderer into `ui`; they parse the **source** delta stream into typed blocks (ADR 0017).
- The status dock is fed from events: phase, token usage, retry and compaction notices.
- Extend the CI source guard (zero terminal reads, zero raw-mode entries) to the v2 entry and REPL classes.
- Nothing but the render loop writes to the terminal.

- [x] Seam 3: screen-truth tests fed by core events through the adapter — the input box is present while idle and
      while streaming; a tool block shows header and result after completion; no live-region residue after a run.
- [x] `AgentStreamRendererTest`'s splitter expectations pass against the `ui` splitter.
- [x] The CI source guard covers the v2 entry and REPL classes and passes.
- [x] `-Pphase16-smoke` and the screen-truth tests named in AGENTS.md pass.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-22 (ticket 16 integration checkpoint).** Source UserEntry.message keeps actual user text and user-origin
attachments; optional presentation contains the combined parsed skill/image content. Submitted prompt blocks should
use actual user input, not the cached model presentation or view-only user messages synthesized for tool images.
Neither UI replay nor context projection may reload referenced files/clipboard; recorded image data is authoritative.

**2026-10-20 (from ticket 03 review).** Render's tool events now take `render.ToolCallView` / `ToolResultView`. Two
things the event → render adapter must do: (1) **hand one batch's results over together** —
`LiveRegionStrip.completeTools` closes the whole tool block on the first matching results list, so forwarding v2's
per-tool end events one by one would silently drop the later ones; (2) fill `ToolResultView.name` / `argumentsJson`
(render uses them to skip results already shown as a diff) — copy them from the matching call if the tool-end event
doesn't carry them.

**2026-10-21 (from ticket 13 review).** Collect immediate `ToolEnd` results and replace them with `ToolBatchEnd.results` when present, then hand the final source-order batch to render at IterationEnd. The finalization event is emitted only when a batch normalizer changes content (MCP aggregate budgeting); ordinary batches still only have ToolEnd events. Never close the whole tool block on the first per-tool completion, or render duplicate results after replacement. ToolEnd stays immediate because completion observers can unblock sibling execution.


**2026-10-23 (ticket22 retry presentation checkpoint).** Headless Composition now owns a queued MAIN-only retry
status writer which drains before answer output; it is presentation, not retry policy. The interactive composition
and event→render adapter must use typed Retry events for dock/status presentation without also installing the
headless writer and duplicating notices. Child retries retain their origin tags. Internal compaction retries are
currently observable only at the ai stream seam and do not publish ordinary MAIN Retry/message iterations; do not
silently claim a broader event contract or count internal requests as trace assistants.


**2026-10-24 (implementation/review checkpoint; final validation pending).** The flag now enters
app/InteractiveEntry, with explicit captured renderer configuration and terminal-free session assembly. The renderer
toolkit is unchanged. UI subscribers only enqueue to a separate consumer; source deltas become typed prose /
reasoning / code, and tool results publish once at IterationEnd after optional finalized-batch replacement. MAIN
only; source prompts use actual input rather than model presentation. Permanent readLine owns keys, shutdown drains
accepted presentation and restores the previous INT handler. The architecture guard now restricts UI to explicit
facade/event/message data; terminal-source guards cover the new entry and REPL.

Independent review found latest-response dock accounting in place of cumulative run metrics, conflated context
occupancy and absent cost. Correction adds owner-captured immutable SessionStatus through the session facade;
projected occupancy includes cached system prompt / framing and actual dynamic tool schemas, cumulative usage comes
from retained completed source assistants, and cost reuses TokenUsageFormatter. Initial/clear capture shares private
tool snapshot composition; observation does not consume prompt invalidation. Idle retains context but blanks usage
and cost, matching actual v1. A second review finding caught status notification skipping old-Agent cleanup on clear
when a listener throws; listener failures now report separately and cannot bypass cleanup or replace primary /
suppressed close failures.

Observed behavior reds are archived separately under /tmp/cafecli-ticket24-evidence/: red-batch, red-entry,
red-separator, red-reasoning, red-status, red-initial-schema, red-status-observer and red-idle-status. Compile / fixture
mistakes (class rename, constructor mismatch, nonexistent test accessor) are not counted as product reds. Final
focused checks **99/0/0/0** cover session status, adapter / splitter, screen / actual piped REPL, architecture, existing
interactive/trace contracts, input source guards and CLI. Source frozen after correction; final suites and root
packaged acceptance still pending. Earlier pre-status smoke456 / screen87 are superseded for final acceptance.


**2026-10-24 (final implementation checks and review).** Same-context final Spec review is clean (0 findings);
Standards has 0 remaining documented-standard breaches. Its optional, non-blocking duplicate-finalization heuristic
in SourceStreamRenderer is recorded without broadening this migration into a splitter redesign. Final post-correction
checks: focused **99/0/0/0** (10 XML), smoke **456/0/0/0** (44), named screen truth **87/0/0/0** (5), quick
**1944/0/0/0** (244), full **2048/0/0/0** (249). All final Maven commands succeeded; the single final full suite took
142.32s. Evidence: `/tmp/cafecli-ticket24-evidence/final/{focused,smoke,screen-truth,quick,full}` and `audit.json`.
Root independently summed the XML, checked each final suite's freshness against command.json (focused has no
command.json), and verified all19 frozen source SHA-256 values in source-manifest.json. Implementer explicitly
released Maven after auditing no live process. Root acceptance checks are still in progress.


**2026-10-24 (leader acceptance).** Independent leader quick **1944/0/0/0**, named **1114/0/0/0**
(137 suites), smoke **456/0/0/0**, named screen/isolation **89/0/0/0**, and clean package all succeeded:
`/tmp/cafecli-ticket24-leader-{quick,named,smoke,screen,package}.log`. No source changes followed the final review /
full-suite manifest; root verified all19 hashes again before packaging.

The rebuilt jar passed `/tmp/cafecli24-jar-check.py`: public session → actual core events → UI adapter → unchanged
InlineRenderer → independent TerminalScreenModel, asserting idle/streaming input, split code, both finalized tool
results exactly once, child suppression, one dock without residue, cumulative owner usage and distinct context
occupancy/cost. Evidence: `/tmp/cafecli24-jar-check-final.log`, fixture directory
`/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-ticket24-ztl4va9a`.
`/tmp/cafecli24-entry-check.py` also passed actual `java -Dcafecli.v2=true -jar` in an automated PTY: resident input,
submitted source, streamed reasoning/code/answer, EOF cleanup and exit0. Evidence: `/tmp/cafecli24-entry-check-final.log`,
`/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-ticket24-entry-32x7d4a4/terminal.bin`.
The root helpers initially used underscore-delimited answer sentinels and expected those source bytes on screen;
Markdown presentation removed the underscores, so the PTY harness never sent its closing EOF and timed out. Plain
sentinels corrected the fixture assumption; no product change was made or product failure claimed.

All16 earlier packaged scripts08,10,10-session,11–16,17-retained,18–23 passed against the same stable jar, including
headless behavior, all capabilities, trace/normalizer and interactive event-loop handoff:
`/tmp/cafecli24-regression-<ticket>.log`. The root-only docs synchronize README / reference / ADR and carry25/26
integration notes. No AGENTS edit, push, unrelated scratch change or real-terminal acceptance occurred; automated
PTY/screen evidence remains offline. Continue25–28; stop before29 for the maintainer's manual-acceptance approval.
