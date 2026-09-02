# What the destructive resize epoch left open for the always-live 输入框

Issues 01–06 of the always-live-input-box tracker landed (ADR 0014 决策 1–5, ADR 0015). The whole
destructive-resize-epoch tracker then landed on top of them (ADR 0010 amendments A1–A19). Those
amendments were designed against the input regime that exists *today*, in which the [[输入框]] lives
only for the duration of a `readLine` and a raw-mode pump owns the terminal for the duration of a
turn (`InteractiveRepl.pumpWaitUntilIdle`). Issues 07–11 replace that regime. Three amendments —
A12 (模态租借), A13 (overlay always available, live follow), A14 (a pending approval closes the
overlay) — therefore rest on premises the remaining issues are about to remove, and one of them
shipped a hazard that ADR 0014 had explicitly designed around.

This ADR records the decisions that unblock issues 07–11. It amends ADR 0010 A13 and revises the
plan in ADR 0014; it does not reopen A1–A11 or A15–A19.

## D1. Entries are immutable source; rendering and its cache belong to the view

A `StripEntry` retained in the ordered commit log exposes **immutable source**. Whoever renders it —
the [[活区]] on the render thread, the [[转写浏览层]] on the input thread — owns the width-keyed
projection and cache that renders it. **No render cache is shared between views.**

### Why

A12 stopped the render loop *writing* while a modal is up, and A13 handed the overlay a live view of
the log rather than a snapshot. But committing deliberately sits **above** the turbulence gate
(`LiveRegionStrip.renderFrame` runs `drainCompletedAnswerRows` before consulting it,
`LiveRegionStrip.java:557`) so that the agent never blocks because a human is reading. The render
thread therefore keeps **mutating** throughout a lease. That is not a flaw in A12 — it is what makes
live follow work at all.

What was never restated is who may touch the mutating object. `AnswerSegment` holds `projections`
and `highlightedProseCache` as plain `HashMap`s (`AnswerSegment.java:21-22`), plus `ArrayList`s
inside each projection. They are written by the render thread every frame through
`freezeTopRows` / `claimTrailingSeparator`, and read *and written* by the input thread every 50 ms
through `OverlayDocument.renderItem` → `entry.render(width, fold)`
(`OverlayDocument.java:290-293`). `ReplayLog` synchronizes the list; `List.copyOf` then hands out
live references to entries it does not protect.

The failure is corruption, not tearing. Worst case first: a `HashMap` resize racing a `put` can leave
a bucket list cyclic, spinning the **input thread** at 100% inside `get` — with the terminal in
alternate screen, and with the overlay being the very thing that reads keys, so there is nobody left
to notice Ctrl+C. Less bad: `wrappedCompletedLines.get(i)` racing the `add` loop in `sync()` throws
`IndexOutOfBoundsException` on that same thread.

Before A13 none of this was reachable: the overlay only opened when idle, and idle means no producer
events, so no mutation. Issue 11 named the hazard and designed it away with a render-thread
snapshot. A13 delivered mid-turn opening without that design, and ADR 0014's rejection of a shared
block-level lock (`0014:29`) points at "ADR 0015 的浏览层快照决定" — a decision ADR 0015 does not
contain. Neither guard was taken.

A second defect shares the root. `OverlayDocument` evicts entries that leave its working set with
`entry.releaseRenderCache()` (`OverlayDocument.java:281`, `:310`). For the streaming answer that
clears the **live region's** `MarkdownProjection`, including `consumedSourceLines` and the
accumulated `TerminalMarkdownRenderer`. Scrolling up in the overlay therefore makes the render thread
replay the whole answer through a fresh markdown renderer on its next frame. Not a race — just
wrong, and only possible because one cache serves two views.

### Mechanism

- `AnswerSegment` keeps only append-only source, and publishes it as an **immutable snapshot behind a
  `volatile` reference**, replaced whenever a line completes. That reference is the happens-before
  edge; a reader gets a consistent list or an older consistent list, never a torn one. Cost is one
  array copy per completed answer line.
- `MemoizedBlock` keeps its source lines — already `List.copyOf`, already safe to share — and gives
  up `renderedByKey`. The cache moves to the view: the live region keeps its own, `OverlayDocument`
  keeps its own keyed by (entry identity, width, fold state). It already tracks a working set, so
  eviction becomes a purely local operation and is **correct by construction** rather than
  accidentally destructive.
- The freeze boundary (`frozenSourceLines`, A11) stays on the entry. It is the live region's own view
  state — which prefix has been committed — and the overlay renders the whole retained entry and
  never asks for it. A11 is unaffected: the boundary is still in source coordinates and still
  re-evaluated per projection.

This restores the invariant issue 11 was built on — **the input thread never touches a block the
render thread owns** — while keeping A13's live follow, which issue 11 had to forbid. It satisfies
ADR 0014's refusal to share a block-level lock without introducing a lock of any kind.

### Rejected

- **Render each item under `RenderLoop.frameLock` from the input thread.** One rule, covers every
  entry type present and future, no per-class redesign, and the overlay already takes that lock twice
  for the lease. Rejected because it *guards* the coupling instead of removing it: the input thread
  becomes able to stall the render thread for the duration of a viewport layout and vice versa, and
  it is the shape `0014:29` rejected, only coarser.
- **Synchronize `AnswerSegment` alone.** Smallest diff, and it is the only entry that mutates after
  commit today. Rejected because "safe because nothing else mutates after commit" and "safe because
  A12 defers the reset until the lease returns" are two unstated premises; the next entry that breaks
  either reopens the hole silently.
- **Revert A13 to issue 11's one-shot snapshot.** Unreachable by construction, but it gives up live
  follow — already confirmed healthy on real hardware — and re-opens "render the whole session at
  open".
- **Concurrent maps on the entry.** Makes the corruption go away and leaves the cross-view eviction
  intact: a data-structure fix for a design defect.

### Consequences

- Issue 11's central argument survives; its mechanism does not. What remains of issue 11 shrinks to
  this decision plus the mouse-tracking timing trap.
- Both views normally run at the terminal width, so today they **share** one projection and that
  cache hit is real. The split runs `TerminalMarkdownRenderer` twice over the streaming answer while
  the overlay is open. Expected small against the measured baseline (18.6 ms to measure a
  6000-item document, 0–17 µs per keypress) — **to be measured, not asserted**, before this is called
  done.
- `releaseRenderCache()` / `renderCacheEntries()` leave the `StripEntry` interface and become
  view-local. `InlineRenderer.retainedLogSnapshot()`'s cache count changes meaning accordingly, and
  `ReplayLog.renderCacheEntries()` — synchronized, but reaching into entries it does not own — goes
  away with them.
- The rule carries its own premise: a `StripEntry` implementation that gains mutable-after-commit
  state is wrong *regardless of who is currently reading it*. That is checkable at review time, which
  "nothing else mutates today" was not.
- **The sharing is verified by reading the code; the failure modes are inferred and have not been
  observed.** Asked directly (2026-08-26), the user has never seen Cafecli disappear while the overlay
  was open, nor stutter after scrolling in it, across the manual testing that produced this branch's
  snapshot commits. The race is real and rarely hit. This decision is therefore taken at normal pace
  rather than as a hotfix, and no effort should go into reproducing it first: succeeding would
  demonstrate a general property of `HashMap` under concurrent mutation, not anything about Cafecli.

### Delivered and measured, 2026-08-27 (issue 11)

Shipped as `AnswerSource` (immutable record behind a `volatile` on `AnswerSegment`),
`AnswerProjection` / `AnswerProjections` (a view's incremental markdown layout), `LiveAnswer` (the
[[活区]]'s view, which is what sits in the strip's live entries while the bare segment is what reaches
`ReplayLog`), and `EntryRendering` / `MemoizedEntryRendering` / `RetainedRenderCache` (the
[[转写浏览层]]'s). `MemoizedBlock` became `BlockSource` and gave up `renderedByKey`; `DiffBlock` gave
up its row map; `StripEntry` lost `renderCacheEntries()` / `releaseRenderCache()` and gained
`committedSource()` and `newRendering()`; `ReplayLog.renderCacheEntries()` is gone.

**The non-race half was reproduced offline before the fix and is now a regression.** Ten scroll
cycles of an overlay over a 40-line streaming answer drove the [[活区]]'s prose-highlight pass count
from **40 to 440** and its cached rows from **80 to 0** — ten full replays of the answer through a
fresh `TerminalMarkdownRenderer`, caused entirely by the overlay's eviction
(`ViewOwnedRenderCacheTest`, measured 2026-08-27). After the split the count is 40, unchanged.

**Cost, measured on the existing instrument** (`OverlayLayoutCost`, 2000 turns / 6000 items, median
of 401 polls or 21 repetitions, n=3 runs each side, same machine, same session):

| | before | after |
|---|---|---|
| open | 35–36 µs | 39–41 µs |
| open at tail | 16.6–17.4 ms | 16.4–16.8 ms |
| per keystroke | 9–10 µs | 8 µs |
| first `G` | 16.4–16.9 ms | 16.3–16.4 ms |
| second `G` | 9 µs | 10 µs |
| idle poll | 13 µs | 12 µs |
| poll while an answer streams at the tail | 149–152 µs | 144–148 µs |

Two columns were added to the harness for this decision, because its synthetic session contained no
`AnswerSegment` at all and therefore could not have seen the case being priced. `ScreenTruthRegression
Test` — which is where the reset path repaints the [[bounded replay window]] from entries that no
longer cache anything — runs 37 tests in 33.56 s before and 33.35 s after (n=1 each).

**"Twice over the streaming answer" is true but too coarse a prediction to measure.** Both views sync
their projection *incrementally*, so what doubles is the per-completed-line work, not a pass over the
answer; against a 33 ms frame and a 50 ms poll it does not rise above the noise. What is genuinely
paid is one extra `TerminalMarkdownRenderer` and one extra row cache per open overlay, and one array
copy per completed answer line for the snapshot.

## D2. Issue 07 retires the polling regime, not the behaviours riding on it

The raw-mode pump (`InteractiveRepl.pumpWaitUntilIdle`) is scheduled for deletion by issue 07. Two
things currently ride on it and do not survive that deletion by themselves.

### Mid-turn Ctrl+O and Ctrl+Y

`Main.handleRuntimeDisplayControl`, reached from the pump at `InteractiveRepl.java:230-231` and
classified at `Main.classifyRuntimeControl:567-574`, is the **only** mid-turn path for both. A13's
mid-turn overlay and the mid-turn HITL toggle are delivered and confirmed on real hardware; issue
07's deletion list names 显示类控制处理 and stops there.

After 07 both become ordinary widgets on the resident reader, and the two entry paths — idle widget
and pump — collapse into one. That is a simplification, but it is not automatic. Issue 07's
acceptance criteria must state that mid-turn Ctrl+O and Ctrl+Y still work and that
`Main.openTranscriptOverlay(Renderer)` remains the single entry, or a shipped behaviour regresses
silently while every gate stays green.

### ESC's latency, which is where the polling regime was doing real work

Today `readRuntimeControl` reads byte 27 itself, takes an 80/20/120 ms burst (`readInputBurst`) and
asks `classifyEscapeSequence` whether it was a standalone ESC or a CSI prefix. Cancel therefore fires
in **≤ ~120 ms**.

As a JLine binding, ESC is a *prefix* of every arrow key and CSI sequence in the keymap, so it is
ambiguous and the keymap waits `ambiguous-binding` before firing the shorter match.
`DEFAULT_AMBIGUOUS_BINDING = 1000L` (`LineReaderImpl.java:107`, applied at `:6557`) and the repo never
sets it (`Main.java:1647-1649` sets only `HISTORY_*`). Issue 07 as written would turn "stop the model"
from ~120 ms into **one second**, including the `⏹️ 已请求取消` feedback — on the key a user presses
precisely because they want it to stop now.

**Decision: set `ambiguous-binding` ≈ 100 ms and delete the hand-rolled classifier**
(`readRuntimeControl` / `classifyRuntimeControl` / `classifyEscapeSequence` / `EscapeSequenceType` /
`readInputBurst` / `RuntimeControl`), as issue 07 planned.

JLine's ambiguity timeout **is** the escape classifier: the same wait-and-see algorithm with a
different window. The classifier is therefore not load-bearing — the window is. Matching the window
we already ship leaves both the latency and the failure mode where they are today: a CSI sequence
fragmented by more than the window mis-fires as a bare ESC, which is already true of
`readInputBurst`'s 80 ms first wait. Same behaviour, less code.

A shorter window (~40 ms) was rejected: a mis-fired cancel is destructive, the difference is below
what a user can perceive against a 33 ms frame cadence, and it would be strictly riskier than what
ships today. Leaving the 1000 ms default and promoting Ctrl+C to the fast key was rejected because it
teaches users the wrong key while ESC keeps the name.

**It must be passed through the `variables` map at construction.** `defaultKeyMaps()` is called from
the `LineReaderImpl` constructor (`:347`) and that is where the value is read, so `setVariable` after
the fact has no effect. This is the same constraint ADR 0014 records for `INPUT_RC_FILE_NAME` — now
with a second instance, which makes it a rule about `variables` rather than a quirk of one key.

### Delivered and measured, 2026-08-28 (issue 07)

The polling regime is gone: `pumpWaitUntilIdle`, `readRuntimeControl`, `classifyRuntimeControl`,
`decideEscCancel`, `readEscCancel`, `classifyEscapeSequence`, `EscapeSequenceType`, `RuntimeControl`,
`readInputBurst`, `handleRuntimeDisplayControl` and `bindEscToClearInput` are deleted, and
`Main.java` / `InteractiveRepl.java` contain zero occurrences of `terminal.reader()` and
`enterRawMode()` — checked by reading those two source files in
`MainInputNormalizationTest.neitherTheAssemblyNorTheReplReadsTheTerminalItself`, because the thing
worth catching is the *next* second key consumer, and no behavioural assertion would go red for it.

**Mid-turn Ctrl+O and Ctrl+Y survived as predicted, and the two entry paths collapsed into one.**
Ctrl+O is one ordinary widget routing through `Main.openTranscriptOverlay(Renderer)` whether or not a
turn is running (`ResidentInputRegimeTest.ctrlCInsideTheOverlayStopsTheTurnAndLeavesTheOverlayOpen`
opens it mid-turn end to end); Ctrl+Y is one ordinary widget that still fires mid-turn
(`InteractiveReplTest.ctrlYStillTogglesHitlWhileATurnIsRunning`, driving `Main`'s real binding on a
resident reader).

**`ambiguous-binding` is asserted as effective, not as set.** The same rig runs twice — once with the
variable, once without — and the ESC-to-cancellation latency is measured on each:
**106 ms configured, 1006 ms on JLine's default** (n=1 each, same run, 2026-08-28,
`ResidentInputRegimeTest.escCancelsWithinTheWindowWeConfigureAndTheDefaultWindowIsMeasurablySlower`).
The control group is the point: without it any implementation that happened to be fast would pass. The
other half of the same window — an arrow key must not be read as a cancel — is
`anArrowKeyIsNotReadAsACancel`. Both construction points pass it through `variables`
(`Main`, `RenderReproDriver`), which is now a rule about `variables` rather than a quirk of
`INPUT_RC_FILE_NAME`.

## D3. Ctrl+C is a signal, so a modal does not narrow it; ESC is a byte, so a modal does

`enterRawMode()` clears `ICANON, ECHO, IEXTEN, IXON, ICRNL, INLCR` and **not `ISIG`**
(`AbstractTerminal.java:194-203`), and the application installs **no `Signal.INT` handler anywhere** —
every `terminal.handle(...)` call is WINCH (`InlineRenderer.java:240/315/360`). So outside `readLine`,
Ctrl+C reaches the JVM default and kills the process.

That makes the PRD's symptom #2 broader than "during a turn": it is every raw-mode modal. On `main`
today, opening the overlay mid-turn (A13, shipped and confirmed on hardware) and pressing Ctrl+C
while reading destroys the session, its history, and its in-memory 任务. The slash palette and
`InlineApprovalPrompter` have the same hole. Issue 08 already calls for a session-level INT handler
for "读取间隙"; what it did not anticipate is that A13 created a read-gap a user can sit inside for
minutes.

**Decision: with the session-level handler in place, Ctrl+C inside the [[转写浏览层]] cancels the
running turn and leaves the overlay open.** ESC continues to close the layer and nothing else.

### Why the two keys diverge here, and why that is not an inconsistency

A13 accepted "回合期要取消得先关层" as a stated cost. That concession was **mechanism-driven**: ESC is
a byte, it has to be read by whoever currently owns the byte stream, and during a [[模态租借]] that
owner is the modal — the cancel classifier never sees it. **SIGINT is not a keystroke.** It is
delivered out-of-band and is unaffected by who is reading. So Ctrl+C is structurally able to do what
ESC cannot, and A13's cost narrows to ESC rather than being a property of modals.

Stated as one rule: *ESC addresses whoever owns the byte stream; Ctrl+C addresses the session.* That
is a mechanical difference, not a convention, which is why it does not reintroduce the
state-dependent key meaning issue 08 exists to remove. The cancel table's one-line rule — 有回合在跑
就停回合 — keeps zero exceptions.

The overlay is **not** closed by the cancellation. Closing takes the screen away from someone
mid-read, and that needs its own justification: A14 has one (a pending [[审批应答]] genuinely needs
the [[输入框]], which is on the main screen), a cancellation has none.

### The two halves ship separately, because their dependencies differ

"Ctrl+C cancels the turn rather than clearing the box" needs the resident reader — it is a key binding
on something issue 07 creates. **"Ctrl+C does not destroy the session" needs nothing.** A
`Signal.INT` handler installed at assembly time is shadowed by JLine's own for the duration of every
`readLine` — where the behaviour is already correct — and is active in exactly the gaps that are
currently bare: the pump, the overlay, the palette, the approval prompter.

Filing both under the full cancel-key table put a defect that is currently destroying user sessions
behind the largest issue in the tracker for no technical reason. The guard is therefore its own
unblocked issue; the table keeps everything that genuinely depends on the resident reader, including
rejecting a pending approval.

**Priced and accepted:** once SIGINT is handled, Ctrl+C stops being the escape hatch for a wedged
process — a hung MCP startup, a stuck tool — because with nothing to cancel the handler does nothing.
A variant that re-raises the default handler when idle was considered and not taken: it buys the
escape hatch back at the cost of a rule with a condition in it. `Ctrl+\` and an external `kill`
remain, and the table's idle double-press-to-exit narrows the gap once issue 08 lands.

### Measured, 2026-08-27 (issue 12), where this section previously said "unverified"

The handler shipped as `cli/SessionInterruptGuard`, installed by `Main` right after the
`CancellationRegistry` it cancels through. Four things were established by test rather than argument
(`SessionInterruptGuardTest`, on a real `LineReaderImpl` over a pipe-backed terminal, signals
dispatched through JLine's own handler registry via `terminal.raise`):

1. **The two handlers do not fight; JLine saves and restores.** `LineReaderImpl.readLine` installs
   `INT -> readLineThread.interrupt()` (`:676`) and restores the previous handler in its `finally`
   (`:799-809`). Ours is therefore shadowed for exactly the duration of a `readLine` and live in
   exactly the gaps. Idle behaviour is unchanged **by construction**, not by convention.
2. **A `UserInterruptException` does not surface from a suspended widget.** With a widget blocked in
   its own `terminal.reader().read()`, the interrupt reaches the *read*, which throws
   `InterruptedIOException` (`NonBlockingPumpReader:81-83`); the widget consumes it, `readLine` keeps
   reading, and no `UserInterruptException` is ever thrown. The session-level handler sees nothing.
3. **Therefore issue 07 must carry this itself.** Once the reader is resident, the session-level
   guard is shadowed *everywhere*, and D3's promise — Ctrl+C inside the overlay cancels the turn and
   leaves the overlay open — has to be delivered by the overlay's own read loop recognising that
   interrupt (or by 07 chaining handlers). This decision's guard covers today's regime, where the
   mid-turn overlay is opened from the pump and is not inside `readLine`; it will not cover 07's.
4. **A real SIGINT reaches the guard on real hardware** (throwaway probe, real pty, `kill -INT`, n=1
   each way): with the guard installed the foreground token is cancelled and the JVM survives;
   without it the JVM dies at the signal. The mechanism is `PosixSysTerminal.handle`, which registers
   a JVM-level handler routing to `raise(INT)` for any non-default handler (`:105-116`). One trap
   worth recording: a process that **inherits `SIG_IGN`** for INT — any background job from a shell
   without job control — cannot have an INT handler installed at all, because HotSpot preserves the
   inherited ignore. An experiment run that way silently measures nothing.

### Carried, 2026-08-28 (issue 07), where point 3 above said it would have to be

The overlay claims the interrupt itself. `TranscriptOverlay` takes an `onInterrupt` runnable —
injected through `InlineRenderer.bindSessionInterrupt`, which `Main` binds to
`SessionInterruptGuard::interrupt`, so it is the same routine and the same scope, not a parallel
semantics. Its `readKey` recognises two shapes, because two readers behave differently: an
`InterruptedIOException` out of the blocked wait (pipe-backed readers, and what issue 12 measured),
and a poll that simply returns with the thread's interrupt flag still set. Either way the flag is
cleared there — it belongs to that keystroke, not to whatever the thread does next — and the method
returns *no key* rather than `q`, which is how "cancel does not close the layer" is expressed.

Asserted end to end on a resident reader over a pipe-backed terminal, with the signal dispatched
through JLine's own handler registry (`ResidentInputRegimeTest
.ctrlCInsideTheOverlayStopsTheTurnAndLeavesTheOverlayOpen`): the turn's token is cancelled, the
terminal is still on the alternate screen afterwards, and only the user's own `q` takes it off.

Two related notes. **The guard's own coverage shrank** — with `readLine` always running it is
shadowed nearly everywhere, and what it still serves is assembly time plus the modals that run
*outside* `readLine` (the slash palette, the project-hook trust prompt). Mid-turn Ctrl+C is now
carried by the `UserInterruptException` that `readLine` throws, routed by `InteractiveRepl` down the
same cancellation path. **And the approval read gap it listed is gone entirely**: approvals are
answered through the [[输入框]], so `InlineApprovalPrompter` — the read that gap belonged to — is
deleted. Issue 12's "Ctrl+C while an approval is waiting stops the turn without answering it" is
preserved and restated for the new regime in `ResidentInputRegimeTest`, which also fixes the scope
split it implies: ESC rejects pending approvals (today's ESC semantics), Ctrl+C does not.

### Completed, 2026-08-28 (issue 08), and one thing this section got wrong

The full cancel-key table ships. Its twelve cells are asserted offline in three places:
`cli/CancelKeyTableTest` (S-B — a real `InteractiveRepl` over pipe-backed streams, no TTY, twelve
cells observed as *what text arrives when Enter is pressed* / *whether the loop exited* / *whether
the turn's token was cancelled*), `cli/CancellationScopeTest` (S-D — scope and turn boundary, on the
real `Agent` ReAct loop with a stub `LlmClient`), and `render/inline/ResidentInputRegimeTest` (the
modal column, against a real [[转写浏览层]] holding the screen).

**Three entry points, one routine.** `SessionInterruptGuard` grew from "the SIGINT fallback" into
*the* cancellation routine: `interrupt()` rejects pending approvals, discards steering, cancels the
foreground turn plus still-[[附着]] tasks, speaks once, and returns whether it stopped anything.
The ESC widget, the `UserInterruptException` out of `readLine`, and the modal's own claimed
interrupt all call it, and `InteractiveRepl` now takes the guard where it used to take a bare
`CancellationRegistry`. The cost of three copies was never duplicate code — it was **three
cancellation scopes that drift apart**, which is exactly the state this section left behind.

**This section's split was wrong, and the table supersedes it.** It said "ESC rejects pending
approvals, Ctrl+C does not". The table's Ctrl+C cell says 同上, and the mechanical reason is
stronger than symmetry: a worker parked on an approval future does **not** wake when a token is
cancelled, so without the rejection "the turn stopped" is not true — the run stays parked and a card
stays on screen asking for y/n. `ResidentInputRegimeTest.ctrlCWhileAnApprovalIsWaitingStopsTheTurn
WithoutAnsweringTheApproval` was therefore **inverted**, not kept green, and is now
`...StopsTheTurnAndRejectsTheApproval`.

**"Visible in the layer" was a claim; it is now measured.** This section justified *not* closing the
overlay partly on the cancellation being readable inside it. That now has an assertion — the
feedback appears on the alternate screen while the layer stays open
(`theCancellationIsVisibleInsideTheOverlayWithoutClosingIt`) — and it was **falsified** before being
believed: with no frame pumped, the phrase never arrives, which is what shows the assertion measures
the ordered-commit-log → live-follow path rather than a stray write. The mechanism is that
`LiveRegionStrip.printAbove` appends to `ReplayLog` inside the render *event*, above the modal
lease's write gate; only the emission is gated.

**ESC lost a job.** Issue 07 shipped `cafecli-cancel-or-clear`, which cleared the [[输入框]] when
idle; it is now `cafecli-cancel` and idle ESC is a no-op, so ESC's meaning no longer depends on
state. Clearing the line is the line editor's own kill-whole-line.

**Ctrl+D needed a liveness guard, not a semantic one.** "No-op while a turn runs" means going back
to `readLine` after an `EndOfFileException` — and a *closed* input stream yields that exception as
fast as the loop turns. The two are indistinguishable at `readLine`; what separates them is
**cadence**, so two EOFs less than 5 ms apart are read as "the stream ended" and exit anyway. A
human cannot produce that interval (key auto-repeat is an order of magnitude slower).

**Not measured:** none of this has been confirmed on a real terminal. What a real-terminal pass
would need to add: press Ctrl+C mid-turn and check the draft is still in the box; press it twice on
an empty box and check the process exits cleanly; open the overlay mid-turn, press Ctrl+C, and read
the cancellation line inside the layer without it closing.

## D4. A captured input frame from another width composes nothing, not stale rows

`LiveRegionStrip.renderEntries` treats the captured frame asymmetrically. The **cursor** guards
against a width mismatch and its javadoc states the principle — *a width mismatch yields `-1` rather
than a wrong position; the next frame carries the corrected one* (`:420-434`). The **rows** two lines
above it do not (`:734-737`):

```java
CaptureDisplay.Frame input = live ? inputFrame() : null;
List<AttributedString> inputLines = input == null ? List.of() : input.lines();
int budget = liveTailBudget(rows, inputLines.size(), dockLines.size());
```

**Decision: when `frame.columns() != width`, compose zero input rows.** The next frame with a matching
capture carries the input box.

The cosmetic half — one frame of input wrapped for the previous width — is the lesser problem. The
real one is that `inputLines.size()` feeds `liveTailBudget`: a buffer captured at width 120 as one row
may need three at width 40, so the tail's budget is derived from the wrong height and the tail can
overrun the [[输入框]] and the status dock. That is a geometry error inside the region the epoch model
exists to keep clean, and it is invisible to any byte assertion.

Today the exposure is idle-only, because `doCleanup` clears the captured frame when a turn takes the
terminal (`CafeCliLineReader.java:169-173`). **After issue 07 the reader is resident, so every reset
during a turn is exposed.**

It also does not always self-correct. The signalled path does — WINCH reaches
`CafeCliLineReader.handleSignal`, JLine redisplays, a fresh capture arrives. But A3c is explicit that
geometry is *sampled, not signalled*: on the heartbeat path the sampler observes a change from a
terminal that sent nothing, the reader never redisplays, and there is **no corrective event** until
the next keystroke. Composing zero rows makes that case degrade to "the input line is briefly absent
on a terminal that sends no signals, until the user types" — which is a bounded, self-resolving state,
where painting stale rows leaves a wrong layout with no scheduled correction.

### Rejected

- **Have the reset ask the reader to redisplay.** Redisplay belongs to the input thread, which may be
  inside a modal; the render thread would have to post and wait on it. Deadlock-shaped, and it would
  make the render thread depend on the input thread for the first time.
- **Re-lay the input line at the new width ourselves.** We receive rendered rows, not the buffer; and
  reproducing JLine's prompt, continuation, right-prompt and completion-menu layout is the mini-fork
  ADR 0014 exists to avoid.
- **Reserve one blank row instead of none.** Keeps the dock from jumping, but assumes a one-row
  buffer: a multi-line input still collapses and grows back. It trades a content glitch for a height
  glitch and is a guess where the chosen rule is a rule.

### Delivered, 2026-08-28 (issue 07)

`LiveRegionStrip.renderEntries` composes `List.of()` when `frame.columns() != width`, so the rows now
follow the rule the cursor already followed.

The regression is a screen-truth one
(`ScreenTruthRegressionTest.anInputFrameCapturedAtAnotherWidthComposesNothingAndCannotOverrunTheDock`):
a draft captured at 120 columns, a resize to 40 delivered on the **heartbeat path** (no signal at all,
so nothing asks the reader to redisplay), and the assertions are that no row laid out for the old
width reaches the screen, that the dock is still the last two rows, and that a capture at the current
width brings the box back laid out for it. Before the change the stale row was on screen; after it,
nothing is until the capture catches up.

**Honest about what the offline gate does and does not show.** The rows half is reproduced. The
*overrun* half is not: JLine's `Display` clips a row that is longer than the width rather than
wrapping it, so offline a stale one-row frame still occupies one row and the budget arithmetic does
not visibly overflow. The budget argument stands on reading the code — `inputLines.size()` is what
`liveTailBudget` subtracts, and it describes a frame that is not the one being composed — and is
**inferred, not measured**. What is measured is that composing nothing removes the wrong rows and
keeps the dock where it belongs.

## D5. A position in the ordered commit log is a source span, not an entry

`ReplayLog.append` fixes an entry's position at its **first** commit and is idempotent by identity
(`ReplayLog.java:43-47`), while a streaming `AnswerSegment` enters the log at its first committed row.
Anything committed while that answer is still growing therefore takes a position *after* the answer's
entire eventual output.

A8b priced this as a coarsening visible only after a [[destructive resize reset]]. It is worse than
that. `OverlayDocument.renderItem` renders each entry with `render(width, fold)`, which for an answer
is `renderRetained` — **every** styled line, not the committed prefix (`AnswerSegment.java:165-178`).
So in the [[转写浏览层]] the mis-ordering is unconditional: a [[转向消息]] submitted mid-answer always
displays after the whole answer, with no resize involved.

That collides head-on with the one thing the steering echo is for. Issue 10 requirement #2 and PRD
user story 3 both claim it 标出它落在回合的哪个位置, and the overlay is precisely where a user goes to
ask that question. The scenario is the common one, not a corner: the model streams, the user reacts
mid-stream, the answer finishes, the steering is injected at the next iteration boundary — the answer
that gets mis-ordered is exactly the one being reacted to.

**Decision: a log position records `(source, committed range)` rather than a whole entry.** An answer
interrupted by another commit occupies two positions, with the interrupting item between them.

This composes with D1 rather than adding a concept: once rendering is a view over immutable source, a
span *is* the shape D1 already introduces, and A11's source coordinates are already the unit the range
is expressed in. With D1 delivered the pieces are already named — an answer's source is
`AnswerSource` (an immutable list of completed lines plus a tail) and the boundary into it is
`AnswerSegment.frozenSourceLines` — so a log position becomes `(AnswerSource, [from, to))` over the
same coordinates rather than a new axis. `AnswerSegment.committedRows(AnswerProjection)` is already
"the rows for source lines `[0, boundary)`"; generalising its lower bound is the whole of the change
on the entry's side. Both consumers change — `ReplayWindow.select` and `OverlayDocument` — and separator
blanks must be suppressed between spans of one answer so it still reads as a single block rather than
sprouting a blank line wherever a steering message happened to land.

### Rejected

- **Accept and weaken requirement #2** to "标出它属于哪一回合". Cheapest, and content is still never
  lost or duplicated. Rejected because the overlay case makes the weaker claim the *only* claim, on
  every viewing rather than only after a resize — the feature would ship with its stated purpose
  unmet in its primary viewer.
- **Write the steering echo into the answer's own source stream.** Positioned by construction, no log
  change. Rejected because the [[提交块]]'s full-width wash would have to live inside a markdown
  projection, and UI chrome would enter the answer's source, which A11's per-width projections would
  then be responsible for laying out.

### Delivered, 2026-08-28 (issue 10)

A position shipped as `render/inline/CommittedSpan` — `(source, from, to)` over the same **source**
coordinates A11 holds the [[冻结边界]] in, with `to == OPEN` while the position is still growing.
`ReplayLog` holds spans instead of entries: appending a different source **closes** the newest
position at its own source's `committedSourceLines()` and **supersedes** the previous position of the
source that is resuming. An entry that is committed in one piece still takes exactly one position and
is still idempotent by identity; `StripEntry.commitsIncrementally()` is the one-line predicate that
separates the two, and `AnswerSegment` is its only true.

**Both consumers changed by receiving positions rather than by being rewritten.** `CommittedSpan`
*is* a `StripEntry`, so `ReplayWindow.select` and `OverlayDocument` kept their types, their
identity-keyed caches (a position is a stable object) and their working-set logic unedited. What a
position decides for itself is exactly the two things the decision named: **which rows** — the source
restricted to `[from, to)`, which on the entry side was `committedRows` / `retainedRows` gaining a
lower bound, as predicted — and **the trailing separator blank**, which `isReplaySealed()` yields
unless a later position of the same source exists. That suppression is what keeps an interrupted
answer reading as one block.

Two smaller consequences worth recording. `ReplayLog.append` refuses to open a position with nothing
in it (`from >= committedSourceLines()`), because an empty position would render as nothing *and* own
its source's trailing blank. And an `EntryRendering` over a position holds the position rather than a
copy of its bounds: a position the [[转写浏览层]] is looking at can close underneath the reader, and
the rows have to follow it down — asserted, and falsified by capturing the bound once
(`SteeringPositionTest.theOverlayFollowsTheCorrectionLandingWhileItIsOpen` then reports the answer's
second half drawn twice).

**Evidence, all offline and all falsified rather than merely passing.** `SteeringPositionTest` (five
tests, in `phase16-smoke`): before the change, the two position assertions failed with the echo after
the whole answer — in the [[转写浏览层]] with **no resize involved**, which is the claim this decision
rests on. The "reads as one block" half is asserted against an independent control: the same answer
streamed with nobody interrupting it, with the correction then deleted from the interrupted log, and
the two must be row-identical in the [[bounded replay window]] and in the overlay. A8b's own
invariant is extended to this case in
`ScreenTruthRegressionTest.aResizeDoesNotReorderAnAnswerInterruptedByASteeringMessage`, which goes
red (measured, by restoring one-position-per-entry) on its post-resize assertion.

**Not measured:** nothing here has been confirmed on a real terminal. `.scratch/destructive-resize-
epoch/issues/11-real-terminal-confirmation-pass.md` still tracks that pass, and what it would need to
add is "steer a running turn, then drag the window, and read the transcript back".

## D6. The box reads as a box through a wash, not through borders

Issue 09's chrome ships as specced — two rules, in-box right prompt, multi-line growth, and the
short-terminal ladder. Two additions and one exclusion decide how it actually reads.

**A vertical border would cost a JLine coupling point; the wash costs nothing.** The rows that make up
the input box are laid out by `LineReaderImpl` against `terminal.getBufferSize()` and handed over
through `CaptureDisplay`. Indenting them for a left border overflows them by the indent, and making
the editor wrap narrower means reporting a width that is not the terminal's — a seventh coupling point
beyond the six ADR 0014 enumerates, and one it did not price. Anything that only *decorates rows we
already have at full width* is free.

So:

- **The input row carries the [[提交块]]'s background wash**, padded to `width - 1` (the same rule the
  status dock follows so the cursor never lands in the last column), branching on the same
  256-colour / 16-colour probe `DiffBlock` and `SubmittedPromptBlock` already use.
- **The `>` marker takes 提交块's purple** (256-colour 141, magenta fallback), so the live marker and
  its frozen `›` counterpart are visibly the same family.
- **The two rules are dim** and span `width - 1`.

The wash is what makes the promise visible. An *empty* bordered-by-rules prompt still looks like a
prompt between two separators; an empty washed row looks like a field that is waiting. And because the
wash is literally the same one 提交块 uses, submitting reads as the field freezing upward into the
transcript rather than as text vanishing and a new block appearing.

### Rejected

- **Corner glyphs (`╭╮╰╯`) on the rules.** Free and imply enclosure, but they depend on the font
  having the glyphs and degrade to boxes when it does not, for a signal the wash already carries.
- **Open-right bracket via the prompt string** (`inputPrompt()` → `"│ > "` with a matching
  `SECONDARY_PROMPT_PATTERN`). This is genuinely free — JLine accounts for prompt width when wrapping
  and already handles continuation prompts — and it was the strongest alternative. Rejected because it
  eats two columns of every input line permanently to say what the wash says for nothing.
- **A closed border on all four sides.** The most unambiguous and what most modern TUIs use, but it
  requires the editor to lay out at `width - 2`, relocating the right prompt JLine has already placed
  at the terminal's right edge, and per-row column-width padding wherever a CJK character abuts the
  border.

### Delivered, 2026-08-28 (issue 09) — offline only

Shipped as `render/inline/InputBoxChrome`, a pure function from captured rows to decorated rows. It
**only decorates rows that already exist and never re-lays them out**, which is what keeps the
seventh coupling point closed: the editor is still given `getTerminal().getBufferSize()` and nothing
else, and the captured frame is never re-clipped or re-padded. No border glyph appears anywhere in the
class except in the comment recording why they were rejected.

Twenty-two assertions in `InputBoxChromeTest` plus four in `LiveRegionStripTest` cover the shape, the
wash (including **the primary criterion — an empty buffer still carries it**), the three colour rungs,
the purple marker against its frozen `›` counterpart, CJK column-width padding, multi-line growth and
internal scrolling, the short-terminal ladder, and D4 compliance (no rows ⇒ no rules and no wash, not
an empty shell of two rules). When the box cannot afford both, **the rules are dropped before the
input row** — decoration always yields to content.

Two things this record deliberately does **not** claim:

1. **The 20/12 thresholds are picked, not measured**, and are labelled as such in the code. The real
   criterion is how many rows of [[活区尾]] remain useful while the status dock, the box and the
   [[bounded replay window]] all squeeze it, and that can only be seen at 80×24 / 80×20 / 80×12 on real
   hardware. **That measurement has not been made.** Do not cite them as data or derive other
   constants from them.
2. **Nothing here is confirmed on a real terminal.** Every assertion is offline, and the screen model
   is a filter rather than an oracle. The visual criteria — rules not breaking or flickering, the box
   not juddering under fast typing, the wash padding cleanly at CJK and emoji trailing characters —
   are unmet pending a human pass, and the issue's checkboxes for them are left unticked.

Two incidental findings, both recorded because they cost real time:

- **`RenderReproDriver` was not extended** for the resident box + long turn (the PRD's S-E path). It
  received only issue 07's `ambiguous-binding` fix. That criterion is unmet.
- **The repository's long-standing "pre-existing order-dependent flake" is a shared-global defect, not
  a markdown bug.** `AnsiStyle.ENABLED` is a `static final` snapshot of `paicli.render.color`, and
  `TerminalMarkdownRendererTest` sets that property to `false` in its static initialiser — so whichever
  test class loads `AnsiStyle` first decides colour for the entire JVM, and
  `wrapsWideMultiColumnTableInsideTerminalWidth` fails whenever it loses that race. Two of issue 09's
  own new assertions were caught by the same mechanism in the opposite direction and were rewritten to
  assert `Palette.fromCapabilities` instead. **The root cause is now diagnosed but not fixed**; fixing
  it is what would finally let `-Pquick` go green.

### First manual acceptance round, 2026-08-28 (issue 09) — three defects, all fixed

The human pass the record above said was owed has now been run, and it found three defects. All three
are fixed, and each was **reproduced offline first, then confirmed to turn the new criterion red when
the fix is reverted**. Two of them turn out to be the same root cause, which is the part worth keeping.

**The shared cause: the input rows are the only live-region rows allowed to reach the last column.**
Everything else the strip composes is padded by us to `width - 1`, precisely so that nothing occupies
the final column. The input rows are exempt because they are not ours — `LineReaderImpl` wraps at the
terminal's own width, and re-wrapping them is the seventh coupling point this ADR rejected. That
exemption was assumed to be free. It is not, and it fails in two directions:

1. **Visually** — a filled row is `width` columns while the rules were a flat `width - 1`, so on any
   long line the wash ran one column past its own border. Measured offline: rules 39, filled row 40.
   The fix grows the rules to the widest **visible** row (`InputBoxChrome.boxWidth`, lower bound
   `width - 1`, upper bound `width`), because clipping the row is forbidden — decoration only ever
   adds columns. An idle box is still `width - 1`, so the last column is still yielded in the common
   case.

2. **Structurally, and much worse** — a filled row puts the *cursor* at `col == columns`, the right
   margin. There is no portable escape for moving there, so JLine's `Display.moveVisualCursorTo`
   **re-prints the character in column `columns - 1`**. When that column holds the second half of a
   double-width character, `columnSubSequence` hands back the whole glyph (it cannot be split);
   printing it one column early overflows, the terminal wraps, and the trailing half lands at column 0
   of the next row. **From that moment JLine's cursor model and the screen disagree by two columns**,
   so every later frame writes to the wrong place: the bottom rule starts at column 2 and the old
   input rows are never cleared. Observed bytes: `ESC[K ESC[4A ESC[39C ESC[48;5;236m 的 ESC[0m`. The
   fix clamps the composed cursor's column to `width - 1` in `LiveRegionStrip.composedCursorPos` —
   which is simply the strip's existing "nothing occupies the last column" rule finally applied to the
   cursor rather than inferred from the rows. The cost is that a row filled exactly to the edge shows
   the cursor on its last character instead of past it, which is the ambiguous position on a
   delayed-wrap terminal anyway.

**The criterion for (2) has to read the screen, and the ASCII asymmetry is part of it.** Every row
involved *was* written — just to the wrong column — so byte assertions all pass. ASCII fills a row
just as completely and does **not** reproduce, because re-printing a single-width character is
harmless. Any explanation that predicts ASCII should also fail is wrong.
`InputBoxLiveRegionTest.backspacingAcrossWrappedCjkRowsLeavesNothingBehind` drives a real
`CafeCliLineReader` and reads `TerminalScreenModel`.

**The third defect is unrelated and was simply missing: no key inserts a newline.** "Newline" has no
key on a terminal; it is a chord each terminal encodes differently, and JLine binds none of them, so
the escape sequence fell through to `self-insert` and printed as text. `InteractiveRepl.bindNewlineKey`
now takes `ESC CR` / `ESC LF` (Option+Enter), `CSI 13;2u` / `CSI 13;5u` (fixterms/kitty) and
`CSI 27;2;13~` / `CSI 27;5;13~` (xterm `modifyOtherKeys`), and sets the secondary prompt to two spaces
so continuation rows align with the body instead of each sprouting a `>` that reads as another
submission. **One rung is not fixable from inside the process**: Terminal.app sends a bare `CR` for
Shift+Enter, byte-identical to Enter. That rung needs a terminal-side rebinding or Option+Enter.

What this round does **not** change: the 20/12 thresholds are still picked rather than measured, and
five of the eight manual checklist items (empty box, submission continuity, right prompt, the
short-terminal ladder, and the slow width drag) were not exercised. The three that were exercised all
need re-running now that they are fixed.
