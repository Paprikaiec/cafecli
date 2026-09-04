# Ordered commit log covers all committed output

Status: done
Type: AFK
Delivered: 2026-08-19 (ADR 0010 amendment A8b). Within-frame write order was **wrong**, not merely
approximate — five per-source pending buffers drained "evicted → frozen → external" while `ReplayLog`
recorded commit order, so a resize reordered the screen (measured: `answer, answer, notice, notice`
→ `notice, notice, answer, answer`). Now one ordered queue, each enqueue paired with its log append.
Correction to the old record: over-long committed rows are **soft-wrapped**, not clipped, so keeping
them at commit width is deliberate; only the 提交块 wash was genuinely baked (`SubmittedPromptItem`).
Offline only — not confirmed on a real terminal.

## Parent

`.scratch/destructive-resize-epoch/PRD.md` — ADR 0010 amendment A8, A17

## What to build

The retained model captures **every** output that reaches [[冻结回卷]], as ordered items in write
order: typed blocks and generic streamed text alike, interleaved. It stops being a five-turn *window*
and becomes the ordered **log** from which bounded views are cut.

Today only typed blocks and the submitted prompt are retained. Generic streamed output — the startup
banner, slash-command results, MCP and Skill summaries, background-task completion notices — is
written straight through to the terminal and retained nowhere.

Under the previous contract that asymmetry was invisible, because unretained output still lived in
native scrollback and the retained model existed only to rebuild expanded form in the
[[转写浏览层]]. The [[destructive resize reset]] changes its meaning entirely: **anything outside the
[[bounded replay window]] is destroyed by the next resize**, so the log's coverage is the product's
memory.

The case that decides it is not degraded presentation but destroyed notification — a background task
completes, prints above the input while the user is looking elsewhere, the window is resized, and the
completion notice is gone. The user may never learn the task finished.

Live-region content is excluded **by definition rather than by policy**: the spinner, approval cards,
the [[输入框]], and the dock are never committed in the first place, so no exclusion list is needed
and no new stream call site has to be classified against one.

Coverage is deliberately not the lever for redraw cost. Generic streamed text is the cheapest row type
in the renderer — one wrap pass, no syntax highlighting, no diff gutter, no table layout — while the
expensive rows are the typed blocks already retained today. Cost is governed by the row budget.

## Acceptance criteria

- [ ] Generic streamed output enters the log in write order, interleaved correctly with typed blocks
- [ ] The startup banner is present in the log
- [ ] Slash-command output is present in the log
- [ ] MCP and Skill summaries are present in the log
- [ ] Background-task completion notices are present in the log
- [ ] Spinner, approval cards, input box, and dock content never enter the log
- [ ] Log contents and ordering are asserted directly against the model, with no terminal and no byte
      inspection
- [ ] After a resize, previously committed generic output is still on screen when it falls within the
      row budget
- [ ] A background-task completion notice survives a resize that occurs immediately after it is
      printed
- [ ] Interleaving is asserted for a sequence that alternates typed blocks and streamed text

## Blocked by

- `.scratch/destructive-resize-epoch/issues/01-destructive-reset-replaces-erase-regime.md`
