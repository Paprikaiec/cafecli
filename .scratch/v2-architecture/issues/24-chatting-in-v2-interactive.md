# 24: Chatting in v2 interactive mode

**Parent:** `.scratch/v2-architecture/PRD.md` (`ui` layer; seam 3) · ADR 0020 D1 · ADR 0010, 0014, 0016, 0017
**Blocked by:** 03, 23
**Status:** ready-for-agent

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

- [ ] Seam 3: screen-truth tests fed by core events through the adapter — the input box is present while idle and
      while streaming; a tool block shows header and result after completion; no live-region residue after a run.
- [ ] `AgentStreamRendererTest`'s splitter expectations pass against the `ui` splitter.
- [ ] The CI source guard covers the v2 entry and REPL classes and passes.
- [ ] `-Pphase16-smoke` and the screen-truth tests named in AGENTS.md pass.
- [ ] `mvn test -Pquick` and the architecture tests are green.

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
