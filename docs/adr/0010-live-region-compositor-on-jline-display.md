# 活动区合成器建在 JLine Display 之上：不 fork、不进主屏 alt-screen

> **Status: AMENDED (2026-08-12).** This block replaces the `REOPENED (2026-08-10)` reopening of
> this same ADR. That reopening proposed a session-long alternate-screen compositor backed by an
> application-owned durable semantic event journal (decisions R1–R7); **that direction is withdrawn**
> and is not being pursued. It answered a resize-fidelity problem by rewriting the renderer, the
> session-history contract, and the Runtime API persistence boundary all at once.
>
> What is amended here is only the resize contract. Cafecli stays on the primary screen and keeps
> using native terminal scrollback; it pays for a geometry change with an explicit, accepted loss
> instead of with a new persistence substrate. The original decision below stands as the baseline
> and remains in force except where decisions A1–A7 state otherwise.

## Amended decisions

### A1. Cafecli stays on the primary screen

A session-long alternate screen is **rejected** for this scope. The interactive renderer keeps
writing to the primary screen, as the original decision and its "被否决的方案" section describe.

This does not touch the [[转写浏览层]]: its existing short-lived alternate-screen use (`Ctrl+O` /
`/transcript`, entered on demand and always restored) is unaffected.

### A2. Native scrollback is used normally while geometry stays stable

While the terminal geometry does not change, completed output goes into native terminal scrollback
exactly as today — blocks freeze out of the [[活区]] into [[冻结回卷]], the terminal owns scrolling,
copy, and search, and Cafecli never repaints what it has committed. Each such period of stable
geometry is a **[[geometry epoch]]**.

### A3. Resize is an intentionally destructive geometry-epoch transition

On a geometry change Cafecli performs a **[[destructive resize reset]]**: it erases the visible
primary screen *and* the terminal's saved scrollback, then redraws a bounded, in-memory
**[[bounded replay window]]** of recent session content together with the current interactive state.

This replaces the attempt to compute an exact erase distance and pin the live region back onto the
row the terminal's reflow put it on. A reset does not need to know where the terminal moved
anything: it declares the previous epoch's screen void and rebuilds from application state at the
new geometry.

### A3a. Any observed dimension change ends the epoch — width and height alike

A [[geometry epoch]] is ended by **every** observed change to either dimension. There is no
width-only, no shrink-only, and no "reflow-relevant" subset.

The tempting narrower rule — only width changes and height shrinks can disturb committed rows — is
contradicted by this repository's own measurements. A height-only change was found to shift the
cursor by one row through a ghost line in the display's own line model, and that silent shift was
what made each *subsequent* width change erase one row short. The dimensions are not independent, so
a trigger that classifies them separately has to be right about their interaction; a trigger that
treats any change as an epoch boundary does not.

This is also the cheap direction to be wrong in. A reset that was not strictly necessary costs one
redraw and, after the first reset of a session, no additional history: the scrollback it would erase
was already erased by that first reset. A missed reset costs a corrupted screen that nothing later
repairs.

### A3b. The reset is split by geometry-sensitivity: erase eagerly, redraw once

An epoch boundary is not an instant. During an animated resize — native fullscreen, a window drag —
dimension changes keep arriving for as long as the animation runs; that interval is
**[[geometry turbulence]]**. A reset is therefore scheduled in two halves, split along the only line
that matters:

- **The erase is geometry-independent.** Erasing the visible screen, erasing saved scrollback, and
  homing the cursor do the same thing whenever they are consumed, at any size. This half is written
  **eagerly, on every observed dimension change**.
- **The redraw is geometry-sensitive.** It is the only part that can be wrong. It is written
  **exactly once**, after the geometry is observed to have settled.

Between the two, **every content write is gated off** — streaming answer text, spinner ticks, dock
updates, freezes. Producers keep updating the source model; nothing reaches the terminal. The screen
is deliberately blank for the duration of the turbulence.

The reason is the boundary the replay work established and could not cross: Java observes when it
submits bytes, not when the terminal consumes them relative to its own reflow queue. Consequently
the only defensible strategy is to keep the queue shallow. Gating makes queue depth one repaint *by
construction*, rather than asserting a timing property Java cannot verify. Drawing a reset per event
would queue one full repaint per dimension change — on the order of two hundred per animation — and
"the last one cleans up" is precisely the claim the measurements refuse to support.

Two consequences follow:

- `RenderLoop.postNow`'s justification retires with the arithmetic it protected. Bypassing the frame
  cadence was mandatory only because the erase distance was computed *relative* to the previous
  frame's width, so a skipped step was unrecoverable. An absolute reset has no per-step dependency.
- The deferral needs a bound. A slow, continuous drag must not blank the terminal indefinitely; the
  redraw is forced after a maximum defer even if the geometry has not settled. The quiet interval and
  that bound are the subject of A3d.

**As implemented** (`TurbulenceGate`, `LiveRegionStrip.renderFrame`). The epoch decision is a state
machine separate from frame composition, and it answers one question per frame — what may this frame
write? — with four permits:

| permit | when | what reaches the terminal |
|---|---|---|
| `CONTENT` | geometry unchanged, no redraw owed | pending [[冻结回卷]] and the composed [[活区]], as within any epoch |
| `ERASE_ONLY` | a dimension change was observed | the erase, and nothing else |
| `NOTHING` | turbulence continues, no new dimension | nothing at all |
| `RESET` | the geometry settled, or the max defer expired | a whole [[destructive resize reset]], then the [[活区]] |

Three consequences of that shape are worth stating, because each is a choice:

- **The redraw carries its own erase.** It could have relied on the eager erase still being the last
  thing on the wire — the gate guarantees nothing was written in between — but a self-contained half
  that homes its own cursor costs a dozen bytes and removes a dependency on that guarantee holding for
  every future writer. It also collapses "keep-alive that coincides with a change" into an ordinary
  `RESET` rather than a fifth permit.
- **Committing to the replay log is never gated.** `drainCompletedAnswerRows` runs on every frame,
  including blank ones. Gating the terminal is the point; gating the source model would lose the
  output that arrived during the gesture, which is the opposite of the intent.
- **A frame requests its successor while a redraw is owed.** A deferred redraw is released *by a
  frame*, and the last dimension change of a gesture is followed by silence — the signal that armed
  the sampler has been consumed and no producer has anything to say. Without this the screen would
  stay blank until something unrelated happened to post an event. Those frames are throttled by the
  ordinary cadence and, while the gate is closed, write nothing.

### A3c. Geometry is sampled, not signalled

"Observed", in A3a and A3b, means **sampled from the kernel**, not "delivered by SIGWINCH".

SIGWINCH is not a faithful record of geometry changes in either direction. One paired capture
recorded 406 delivered WINCH markers against 284 distinct sampled geometry transitions: signals
arrive without a new geometry, and distinct geometries are coalesced out of signal delivery. An
epoch model built on the signal would both start epochs that never happened and miss ones that did.

The channel is therefore a native `TIOCGWINSZ` probe. SIGWINCH is demoted to a wake-up hint: it arms
the sampler, which polls at roughly millisecond resolution for the duration of the
[[geometry turbulence]] and parks once the geometry settles. Idle sessions pay nothing. A slow idle
heartbeat covers terminals that expose no signal handling at all, which the renderer already
tolerates today.

This also removes the largest fixed cost on the write path. Frame composition currently asks the
terminal for its size, which on an exec-pty forks `stty` — measured at a 3.3 ms median, 10.7 ms p90,
about half of a frame. A sampled geometry is a field read. The standing rule that geometry must not
be re-measured immediately before writing was a consequence of that fork, and it retires with it.

**As implemented** (`GeometrySampler`, `GeometrySource`, `replay/NativeTerminalSizeProbe`). Sampling
is a **push**: every probe belongs either to the signal thread or to the sampler's own driver, and
`sample()` — the only thing frame composition calls — is a volatile field read. Three parts are worth
stating because each is a choice:

- **The signal takes the probe inline.** `arm()` does not merely set a flag: it samples on the spot
  and *then* unparks the driver, which is the fastest arming available and makes the signalled path
  deterministic without any thread. It remains a wake-up hint rather than an authority — a signal
  arriving without a dimension change observes no transition and ends no epoch.
- **The polling policy is a pure step.** `pollAt(nowNanos)` probes, publishes, and returns how long
  to wait next: `DEFAULT_FAST_POLL_MILLIS` (1) while the geometry has moved within the last
  `DEFAULT_SETTLE_MILLIS` (the quiet interval of A3d — the sampler must stay awake at least as long
  as the gate might still be deciding), otherwise `DEFAULT_HEARTBEAT_MILLIS` (500). Taking the
  timestamp as an argument rather than reading a clock is what makes parking, waking and the
  heartbeat assertable with fabricated time and no thread.
- **Whether a source may be polled fast is a question about cost, not accuracy**
  (`GeometrySource.pollableAtMillisecondResolution`). The native ioctl may; `Terminal.getSize()` on
  an exec-pty must never be, because polling a subprocess fork a thousand times a second is worse
  than the problem. So a session without the native probe **keeps working and simply never opens the
  fast window**: the signal still arms an immediate probe and the heartbeat still covers a terminal
  that signals nothing. A native probe that stops answering degrades stickily, taking the fast
  cadence with it rather than handing it to the fork.

The probe itself is **promoted, not rewritten**: `NativeTerminalSizeProbe` was built for the replay
recorder, and both callers now share it along with its packed wire format. It is selected only for an
`AbstractPosixTerminal`, because it reads file descriptor 1 — on any other terminal that descriptor
is not the terminal being rendered to.

Two consequences reach beyond the sampler:

- **An observation off the render thread is worthless without a frame to consume it.** A
  [[geometry epoch]] ends on a frame, and after the last change of a gesture — or always, on a
  terminal that signals nothing — nothing else posts one. The sampler therefore asks for that frame
  through the same seam a deferred redraw uses (`LiveRegionStrip.bindFrameRequest`).
- **Geometry observation is established by `Renderer.start()`**, which is where the wake-up hint is
  wired and where the driver comes up. `start()` already owned the WINCH handler, so this makes one
  rule out of two half-rules; the price is that a renderer constructed but never started does not
  react to a resize. That is a supported-configuration statement, not a defect: drawing is no longer
  a third way to observe geometry, and it was only ever one because it forked.

### A3d. The quiet interval and the max defer are measured, not chosen

Both constants are derived from a captured turbulence trace, not argued from first principles. The
sampler of A3c already emits timestamped geometry transitions at millisecond resolution, so the
arrival-side distribution — inter-change gaps, and total turbulence duration per gesture — is
directly observable for both the fullscreen animation and a drag.

Measuring is legitimate here only because of the gate. Releasing the redraw requires both that the
window has stopped changing and that the terminal has drained what was already queued; the second is
the boundary Java cannot observe, and fitting a delay to it would be circular. The gate makes it true
by construction instead — nothing but a dozen bytes of erase is written during turbulence — leaving
the interval to satisfy only the first condition, which is exactly what the sampler measures.

**Measured 2026-08-13** (5 captures: 3 × `fullscreen 6`, 2 × `drag 4`, native sampler at 1 ms):

| | fullscreen (authentic gesture) | drag (synthetic) |
|---|---|---|
| intra-gesture gap | p50 8.8 ms, p95 33.8, p99 41.6, **max 42.8** (n=800) | p50 57.9, p99 113.6, max 117.3 (n=654) |
| gesture duration | p50 274.9 ms, **max 275.7** (n=36) | one continuous 19.0 s sweep (n=2) |
| changes per gesture | 21–25 | 328 |

The three fullscreen runs agree closely; its ~275 ms duration is a fixed macOS animation constant.
The drag figures are **a floor, not a measurement of a human drag** — `scripts/resize-storm.sh` steps
it on a scripted 50 ms sleep, and a real hand-drag fires at display refresh, closer to the fullscreen
figures.

**Quiet interval: 100 ms.** It clears the authentic gesture's worst observed gap by 2.3×. It does not
cover the synthetic drag's tail (about 7 gaps per run land in 100–200 ms), and that is accepted: a
premature redraw is self-correcting, since the next dimension change erases and repaints it, and A9
measures the whole repaint at ≤12 KB, so a handful of them cannot recreate the queue depth A3b
exists to prevent. 120 ms is the conservative alternative if that tail is judged to matter.

**Max defer: 500 ms.** Above the fullscreen maximum of 275.7 ms, so the authentic gesture never pays
a wasted mid-animation repaint. It matters for long drags instead: the 19-second synthetic sweep
would cap blank screen at half a second and take roughly 38 keep-alive repaints rather than 328.

**Both are overridable**, because a terminal or a gesture nobody has measured may need different
numbers: `-Dcafecli.render.turbulence.quiet-ms` and `-Dcafecli.render.turbulence.max-defer-ms`. A
malformed or negative value falls back to the measured default rather than failing startup — a tuning
knob must not be able to stop the renderer from coming up.

**Both are counted from an observation, not from an event in the world.** The quiet interval runs from
the frame that *observed* the change and the max defer from the last redraw written, which is all a
sampler can know: the window may have stopped moving before anything looked. Counting the max defer
from the last redraw rather than from the start of the gesture is what makes a long drag come back
*periodically* instead of once.

Rejected for now, and not to be introduced as an implementation detail: releasing the redraw on a
terminal acknowledgement fence — writing a DSR and waiting for the reply — which is the only known
way to observe the consumption side directly. It requires a second terminal reader, which ADR 0014
forbids and which the renderer documents as a deadlock bug. It remains the upgrade path if the timing
approach proves unstable, but it is a separate decision.

### A4. The cost is accepted, and must be stated plainly

A [[destructive resize reset]] erases scrollback that Cafecli did not write — including shell
history from before Cafecli started — and every line of Cafecli output that falls outside the
[[bounded replay window]]. Native scrollback is therefore **not durable across resize**; it is
durable only within one [[geometry epoch]].

This is the price of the amendment, not a defect to be quietly minimized. It is to be documented for
users in those words rather than softened.

A10 changes the shape of that price for Cafecli's own output, and only for that. Session output
outside the replay window stops being reachable through the terminal's scroll, copy, and search, but
it survives in the retained model and stays readable in the [[转写浏览层]] until the process exits.
Scrollback that Cafecli never wrote — the shell history preceding it — has no such refuge and is
simply gone.

### A5. Explicitly out of scope for this decision

The following were the withdrawn R1–R7 direction and are **not** being pursued:

- full-session recovery after shutdown or crash;
- a durable semantic transcript journal as the authoritative session record;
- coupling the renderer to the Runtime API event substrate;
- virtualized full-screen history with an anchor-centric layout engine;
- exit-time transcript reconstruction.

Nothing in A1–A4 requires any of them. Reintroducing one is a new decision, not an implementation
detail of this one.

### A6. No universal duplicate-free guarantee

A terminal that does not honor erase-saved-scrollback cannot be promised duplicate-free resize
behavior. On such terminals Cafecli degrades honestly rather than claiming a correctness it cannot
deliver, and the product contract keeps the explicit non-rich fallback that already exists
(`CAFECLI_RENDERER=plain`).

### A7. ADR 0014 stands

Unchanged by this amendment: the single-terminal-writer invariant (the render loop is the only
writer of live-region frames) and the captured-JLine-input decision (the line editor yields the
brush). A [[destructive resize reset]] is issued by that one writer like any other frame.

## Replay window decisions

### A8. Everything committed is replayable, in one ordered model

The [[bounded replay window]] captures **every** output that reaches [[冻结回卷]], as ordered items
in write order: typed blocks and generic streamed text alike, interleaved. Live-region content is
excluded by definition rather than by policy — the spinner, approval cards, the input box, and the
dock are never committed in the first place.

Today's five-turn retained model captures only typed blocks and the submitted prompt. Generic
streamed output — the startup banner, `/mcp`, `/task`, `/hooks` and other slash-command results,
MCP and Skill summaries, background-task completion notices — is written straight through to the
terminal and retained nowhere.

Under the original contract that asymmetry was invisible: unretained output still lived in native
scrollback, and the retained model existed only to rebuild expanded form in the [[转写浏览层]]. A3
and A4 change its meaning entirely. **Anything outside the replay window is destroyed by the next
resize**, so the window's coverage is no longer an overlay convenience — it is the product's memory.

The case that decides it is not degraded presentation but destroyed notification: a background task
completes, prints above the input while the user is looking elsewhere, the window is resized, and the
completion notice is gone. The user may never learn the task finished.

Coverage is deliberately *not* the lever for redraw cost. Generic streamed text is the cheapest row
type in the renderer — one wrap pass, no syntax highlighting, no diff gutter, no table layout — while
the expensive rows are the typed blocks already retained today. Redraw cost is governed by row count,
which A9 bounds. Buying a faster redraw by dropping output from the model would pay in silent data
loss for a saving that is not where the cost is.

### A8a. `stream()` commits through the live region unconditionally — `isReading()` is not a routing decision

**Measured, 2026-08-17 (n=3, byte-identical):** with A1–A3d and A17 delivered and the offline suite
fully green, `scripts/resize-storm.sh fullscreen 6` reported
`fullscreen spinner=1 dock=1 replay=0/60 FAIL`. The copy half of the contract held; the preservation
half did not. Every one of the 60 numbered transcript lines was gone from Terminal.app history after
the gesture — a resize destroyed the entire transcript, which is strictly worse than the regime A3
replaced, because that one at least left native scrollback alone.

**Mechanism (measured offline, `liveRegion().replayLogSize()` = 0 after 20 `stream().println`):**
`InlineRenderer.emit` routed generic streamed transcript text on `LineReader.isReading()` — through
`printAbove` while the line editor was inside `readLine`, and through a direct producer-thread
`writeDirect` otherwise. Only the first branch reaches `LiveRegionStrip.printAbove`, and therefore
only the first branch reaches `ReplayLog`. Outside `readLine` — which is most of a running turn, and
the whole of the resize-storm target, which never calls `readLine` at all — committed text was
written to the terminal and recorded nowhere. The eager erase then took it and the deferred redraw
had nothing to bring it back from.

The routing predicate was a leftover of the pre-ADR-0014 regime, when `printAbove` was still how
output got above a line editor that owned the input line. Under 决策 2 the [[活区]] owns the
[[输入框]] permanently, so `isReading()` no longer distinguishes two situations that differ — and
`writeDirect` from a producer thread is a second writer besides, contradicting "RenderLoop is the
only writer".

**Decision.** Everything `InlineRenderer` commits to [[冻结回卷]] goes to the strip, which records it
in the ordered commit log and lets the render thread write it out. `printAbove` survives for exactly
one configuration: a terminal too poor for `CafeCliLineReader`, whose stock line editor draws its own
input line and must be asked to scroll text above it. That case is identified by
`inputLineOwnedByLiveRegion`, not by `isReading()`.

**Why this was not caught.** `ScreenTruthRegressionTest` built every transcript through
`beginAnswer()` and had zero `stream()` call sites, so the [[bounded replay window]] was only ever
proven over typed blocks — the one commit path that was already logged. The standing gate now
exercises the streamed path across the measured fullscreen gesture
(`transcriptStreamedThroughTheRendererSurvivesTheFullscreenGesture`); with the fix reverted it fails
`expected: <1> but was: <0>`, i.e. it reproduces `replay=0/N` offline.

This closed the first acceptance criterion of the ordered-commit-log slice (issue 04) for
`Renderer.stream()`; A8b closes the rest of it.

### A8b. Commit order is one queue, and that queue's order is the log's order

**Measured 2026-08-19.** Within a single frame the strip wrote committed rows in source-of-origin
order — evicted rows, then frozen block rows, then external text — while `ReplayLog` recorded them in
commit order. The two therefore disagreed, and a resize made the disagreement visible: a frame that
streamed two notices and froze two settled answers put `answer one, answer two, notice one,
notice two` on screen, and the reset after the next resize repainted
`notice one, notice two, answer one, answer two`
(`ScreenTruthRegressionTest.aResizeDoesNotReorderInterleavedStreamedTextAndTypedBlocks`). Committing
one thing per frame hid it: the per-frame variant of the same test was already green.

**Decision.** One ordered queue of pending commits, drained in order in a single pass, with every
enqueue paired with its `ReplayLog.append`. The five buffers it replaces (evicted rows, frozen block
rows, prompt rows, prompt text, external text) are gone, along with the second clear/emit/install
cycle the prompt buffers needed.

The gating property is stated as an invariant rather than as a chosen order: **the sequence of
committed rows on screen after a resize is the sequence that was on screen before it.** Which order is
"right" when a streamed notice races a typed block that has not settled yet is a judgement — a block is
committed when it settles, not when its content was produced — but screen and log disagreeing is never
right, because that disagreement is exactly what a resize surfaces.

**Granularity, accepted:** the log orders *items*, so an item that commits incrementally (a streaming
answer) can be split in scrollback by another item committed between two of its drains. Replay then
shows the answer's whole committed prefix followed by the notice, rather than the notice inside it. No
content is lost or duplicated; only that one interleaving is coarsened. Splitting the answer into
several log items would break A11's prefix semantics, which is the worse trade.

**Coverage of A8's named sources is closed and asserted against the model, not the terminal**
(`OrderedCommitLogTest`, ADR-mandated seam: the log as a plain ordered list). They reach it through
exactly two seams and no third: the startup banner through the intercepted `printAbove` that
`installStartupScreen` hooks onto JLine's `CALLBACK_INIT`, and MCP/Skill summaries, slash-command
results and background-task completion notices through `Renderer.stream()`. Live-region content is
absent by definition — the spinner, the approval card, the [[输入框]] and the dock are never committed,
so there is no exclusion list and no new call site to classify.

**One claim recorded here was wrong, and the correction matters because it would have bought a
regression.** The slice recorded that streamed rows, captured already laid out, are *clipped* by a
narrowing resize. They are not: an over-long committed row is **soft-wrapped**, so the content survives
whole and a copy rejoins it (measured 2026-08-19, `committedStreamedOutputSurvivesNarrowingWhenItFitsThe
RowBudget`: 8 lines of 79 columns committed at width 100 and repainted at 60, each recovered exactly
once from the model's `history of tab` serialization). Re-folding them into hard rows at replay width
would have split one logical line into two for copy, search and history — worse, not better. What
baking actually costs is row-budget accuracy, and A9's budget is advisory anyway because whole items
render. So generic committed text keeps the producer's own bytes.

The one place a baked width *was* visible is the [[提交块]], whose dim wash is padded to the full
terminal width: a row baked at 100 columns and repainted at 60 left a 40-column stub of wash on a
second row. That was an accepted cost while [[冻结回卷]] was never reflowed and is not one now, so the
submitted prompt is retained as its **source** (`SubmittedPromptItem`) and re-rendered by the reset —
which also moves its layout onto the render thread, at the frame's own width instead of one sampled
off-thread.

### A9. The replay bound is a row budget in screenfuls, decoupled from retention

A reset repaints the newest few screenfuls of rows — a budget proportional to the current terminal
height, not a number of turns. Items are rendered backwards from the newest until the budget fills;
the overflow scrolls into the freshly erased scrollback, so the user still has real scrollback to
page up into.

**Retention and replay are different bounds serving different consumers.** What is kept in memory
answers to the [[转写浏览层]], which costs no terminal I/O and is opened deliberately. What is
repainted answers to the terminal, automatically, on every geometry change, where every row past the
screen height forces a scroll. Collapsing them into one number would let the cheap consumer set the
expensive consumer's cost.

A turn-based bound is the wrong shape here because it is unbounded in rows: one turn may hold several
code and diff blocks plus an arbitrarily long answer, so five turns is plausibly five hundred to
fifteen hundred rows, and the redraw would get slower the longer the session ran. A row budget also
bounds *render* cost, not just paint cost — rendering backwards from the newest means the blocks
beyond the budget are never laid out at the new width at all, which matters because the render cache
is keyed on width and a resize misses every entry by construction.

Consequence, accepted: which items survive on screen becomes width-dependent, since narrowing makes
the same content occupy more rows. That is correct for a bound whose purpose is to cap cost.

**Measured 2026-08-13, and the result reframes this decision.** Walking retained blocks newest-first
until the budget fills, on the cold path (cache released each pass, one fresh width per sample, 120
widths, realistic mix of 33 blocks / 717 rows) costs at a 44-row height:

| multiplier | budget | render p50 | repaint volume |
|---|---|---|---|
| ×1 | 44 rows | 183 µs | 2.5 KB |
| ×2 | 88 rows | 185 µs | 5.0 KB |
| ×3 | 132 rows | 218 µs | 7.5 KB |
| ×5 | 220 rows | 313 µs | 12.4 KB |

**Render cost does not constrain the multiplier.** Going from one screenful to five costs about 130
microseconds and 10 KB — three orders of magnitude below the quiet interval that precedes it. The
earlier reasoning that a taller replay window would make the redraw perceptibly slow was wrong about
the render half. Whatever cost remains is the terminal's paint of those rows, which is the boundary
Java cannot observe, so the multiplier is chosen for how much scrollback the user should keep rather
than for cost. **×3 is the default.**

One measured consequence worth designing against: **the budget is advisory, because whole blocks are
rendered.** A 100-line code block cannot fill a 44-row budget precisely — a 44-row budget rendered
104 rows, a 132-row budget rendered 207. Either the overshoot is accepted, or the oldest block in the
window is rendered partially. Block granularity, not the multiplier, decides the real repaint size.

### A10. Retention is the whole session; the overlay lays out only its viewport

The retained model keeps **every** turn of the running session, not the newest five, and the
[[转写浏览层]] is where a user reviews it.

This is the complement to A3 and A4, not an unrelated feature. A policy that treats native scrollback
as disposable needs somewhere for history to live; without it, a resize is simply amnesia. With it,
the accepted cost changes shape: output outside the [[bounded replay window]] stops being reachable
through the terminal's own scroll, copy, and search, but it is not destroyed.

Making that deliverable requires one change: the overlay currently renders the entire document and
then slices the viewport out of it, on every keystroke. At five turns that is invisible; at
session length it rebuilds a five-figure row list per arrow-key press. It must instead lay out only
the rows the viewport shows.

That is explicitly **not** the virtualization A5 excludes. R7 proposed paging payloads from a session
store, semantic-anchor-based positioning, and a bounded layout cache keyed by block
revision. None of that is adopted here. "Do not build ten thousand rows in order to show forty" is a
contained property of one rendering path, and buys nothing beyond itself.

Memory tracks retained *source*, which is small; rendered rows are cached per width and released
outside the working set, which is where the cost would otherwise accumulate.

Deliberate, because users will come to rely on it and will then expect otherwise: this history is
in-memory only and dies with the process. A5 is unchanged — there is no durable journal, no recovery,
no reconstruction after exit. `/clear` remains the one way to drop it during a session.

**Delivered 2026-08-19.** The five-turn `RetainedWindow` is deleted, not widened: the ordered commit
log *is* the retained model, so there is no second copy of the session and no eviction left to get
wrong. The overlay browses the log directly, which is what gives it the startup banner, slash-command
output and background notices as well as typed blocks, and gives it the [[提交块]] in **source** form
so it re-renders at the width being browsed.

*The cost criteria are measured, not asserted, and the gate is a counter.* A wall-clock assertion
would be flaky, and a functional assertion cannot see the difference at all — the old whole-document
layout produced exactly the same rows. So the regression gate counts layouts
(`OverlayDocumentTest`: 2000 items, 40-row viewport → at most 20 items laid out on open, at most 12
re-laid-out per line scrolled; reverting to whole-document layout fails three of the five), and the
numbers come from a dev-only harness (`OverlayLayoutCost`, median, fresh document per repetition,
per-turn prompt + 24-line code block + 6-line reasoning block, width 118, page 43):

| turns / items | open | per keystroke | first `G` | later `G` |
|---|---|---|---|---|
| 5 / 15 | 47 µs | 0 µs | 41 µs | 13 µs |
| 50 / 150 | 54 µs | 17 µs | 718 µs | 17 µs |
| 500 / 1500 | 56 µs | 17 µs | 7.4 ms | 19 µs |
| 2000 / 6000 | 47 µs | 14 µs | 17.7 ms | 11 µs |

Opening and line-scrolling are flat in session length, which is the criterion. **Jumping to the end
is not, on its first use at a given width and fold mode, and that is a consequence of refusing
semantic anchors rather than an oversight.** The viewport is an absolute row range, so "the last page"
cannot be named without knowing the document's height, and the height cannot be known without
measuring every item. Heights are then remembered, so later jumps are flat. The alternative — holding
the viewport as an item anchor so the tail is reachable without measuring — is exactly the
anchor-centric positioning A5 excludes, and it would also decide where the overlay *opens*: this one
opened at the top, and moving that to the tail (A13, delivered) meant paying the same measure once at
open — measured at 18.6 ms for 6000 items, against 18.1 ms for the first `G`.

### A11. The freeze boundary is width-independent and epoch-scoped

Every position that divides "already committed" from "still live" is expressed in **source**
coordinates, never in rendered or styled-line coordinates, and is re-derived at the current width.

The streaming answer is the case that forces this. It already projects itself per width and memoizes
each projection, but it tracks how much has been committed as a single count of *styled* lines —
and the same source rendered at a different width yields a different number of styled lines, because
markdown reflows and tables re-allocate their columns. Carried across a width change, that count
under-counts and silently empties the live tail, or over-counts and repeats committed content. The
projection already records a per-source-line cumulative styled count, so the boundary can be held in
source space and mapped into whichever projection is current.

Under the original contract this was a small tail glitch, masked by immutable scrollback. Under A3 it
is central: a reset repaints from source at a new width, so any boundary carried in rendered
coordinates is wrong exactly when it is used.

Because a reset repaints what it erased, freeze boundaries are also **per [[geometry epoch]]**. A new
epoch begins with the boundary re-derived at the new geometry rather than inherited from the old one.

**Delivered 2026-08-19, and three things measured along the way sharpen this decision.**

*The mechanism is narrower than "markdown reflows".* `TerminalMarkdownRenderer` wraps only table
**cells** to the width; prose is folded into rows later, when a styled line becomes physical rows, and
therefore outside the styled-line count. So the count of styled lines a given source produces is
width-**independent** for prose and width-dependent for a table, whose row height is its tallest
wrapped cell. A table in flight is not one case among several — it is the case. (A three-column table
that produces 13 styled lines at width 40, 10 at 60, 9 at 80 and 7 at 120. Two columns will not
reproduce it: two-column tables with long cells degrade into a width-independent key/value form, which
is why the first attempt at a failing test was green by accident.)

*The break is one-directional.* Under-counting — narrowing — costs nothing visible: within one
projection the styled index still partitions the answer into a committed prefix and a live tail, and
both are written, in order. Over-counting — **widening** — is the real defect, and it is worse than a
tail glitch: the boundary exceeds the new projection's total, so the drain commits nothing and the tail
renders nothing, and everything the answer streams after the resize is invisible for good. Measured
against the pre-change code, the only red cases are widening and a multi-epoch run ending in one, and
they fail by *absence of content on screen*
(`ScreenTruthRegressionTest.wideningMidAnswerNeitherTruncatesTheTailNorRepeatsCommittedContent`).

*The boundary may only land where markdown has flushed.* Holding it as a count of committed source
lines is not by itself enough: a block emits nothing until it closes, so a boundary *inside* an
unclosed block owns rows no projection can place. Where the flushes happen is a property of the source
and identical at every width — only how many rows they produce is width-dependent — so committing steps
over source lines that flushed nothing as part of committing the line that flushed them, and never
advances while nothing at all has been emitted. This **deleted** the retroactive attribution of
deferred output (the pass that charged a closed block's rows back to the source lines inside it, plus
the finish-time rewrite of the last cumulative count). Both could rewrite the cumulative entry of a
line the boundary had already passed, which marks rows as committed that were never written — losing
them outright. That was found by a failing test, not by inspection: the first implementation kept the
attribution pass and emptied the tail in *both* directions.

The gating assertion is the property this decision exists for, and it references no boundary and no row
count: an answer resized mid-stream ends up showing exactly what the same answer shows when it was
never resized at that width. It is instantiated four ways — narrowing, widening, a resize with a table
still open, and a run crossing several epochs — because each perturbs the styled-line count
differently.

### A12. A modal leases the terminal; a reset that arrives meanwhile is deferred to its return

While a modal owns the terminal — the [[转写浏览层]], the slash palette — the render loop writes
nothing. A geometry change is still observed and still ends the [[geometry epoch]], but the
[[destructive resize reset]] is *deferred*: exactly one fires on return if the geometry changed while
the modal was open, and none fires if it did not. The modal handles its own geometry change
internally, re-measuring and re-rendering at the new size.

This extends ADR 0014's single-writer invariant rather than bending it: one writer owns the terminal
at a time, and the modal is that writer for its duration.

It also closes a defect that predates this amendment. The overlay installs no suspend/restore pair,
reasoning that its fullscreen display already owns the screen — which holds only because the overlay
is idle-only, so no producer events means no frames. But a resize posts a frame regardless of
idleness, so resizing during an open overlay already paints live-region rows onto the alternate
screen, over it; and the overlay samples its page size and display geometry once at open and never
re-measures. Under A3 the same path would erase the screen and saved scrollback while the alternate
screen is active, then restore a primary screen it had just erased.

Not trusting `rmcup` to restore a primary screen across a geometry change is deliberate. The terminal
reflowed that content while we were not looking, which is the situation this whole amendment exists
to stop trusting.

**Delivered 2026-08-19.** The lease is a depth counter on the strip and a **per-frame fact** handed to
the turbulence gate, alongside the geometry and the clock — the gate needs to know whether it may
write, not who is holding the terminal. Under a lease every permit is `NOTHING`; a dimension change is
recorded exactly as it always was, so the ordinary quiet-interval path yields the single reset on the
first frame that is allowed to write, and an unchanged geometry yields `CONTENT` with nothing
repainted. Nothing was added for "exactly one" — it falls out of the existing state machine, which is
why rapid open/close across a resize cannot strand the owed reset.

Three things this delivery settled that the amendment did not say:

*The handover needs a frame boundary, not just a flag.* Composing a frame and writing it were already
one step on the render thread but not one critical section, so a frame composed before the lease could
still be written after it — onto the alternate screen. `RenderLoop.atFrameBoundary` makes compose and
write one section and takes the lease inside it; the action only flips a counter, so the render thread
is never held for longer than that.

*A leased frame must not ask for another one.* Frames are self-requesting while a redraw is owed, and
a modal is open for as long as a user reads, so without suppressing that the session would spin at the
frame cadence writing nothing. The owed redraw is picked up by the single frame the release asks for.

*The overlay has to poll for keys.* It holds the terminal, so nobody else is allowed to fix its
screen; a blocking read would leave a resized window mangled until the user pressed something. It now
polls at 50 ms and compares the sampled geometry, then re-measures its page size, installs a fresh
`Display` (A18: resizing one leaves a ghost line) and erases with `CSI 2 J` — the visible screen only.
The saved-scrollback erase is deliberately **not** used here: the alternate screen has no scrollback of
its own, and the scrollback the terminal holds belongs to the primary screen underneath, which no
window change of the user's asked to lose.

### A13. The overlay is always available and follows live; its missing input box is the boundary

The [[转写浏览层]] opens at any time, including during an active turn, and follows new output while
open: pinned at the tail it advances, scrolled up it holds position.

The glossary has said "空闲与回合运行中都可进入" all along; the key binding gates on idle and prints
a hint instead. This decision makes the code match the documented intent rather than changing
direction. Mid-turn access is also the case that makes liveness matter — the reason to open it during
a turn is to see what is happening now.

The previous rejection of live following rested on two premises, both now false: that it would
require re-rendering the whole retained window every frame, which A10's viewport-only layout removes,
and that it would require sharing a block-level lock between the input and render threads, which the
retained model's existing synchronization already removes. The overlay's key read already polls with
a timeout, so following is a version check on that existing loop.

Two consequences to accept knowingly. ESC inside the overlay closes the overlay and does not cancel
the turn, so cancelling during browsing means closing first. And the overlay's line-scroll replaces
the terminal's own scroll, copy, and search for as long as it is open.

**The absent [[输入框]] is a boundary, not an omission.** Full session history, live following, and
self-managed resize together are most of a full-screen compositor. Adding an input box to the overlay
is the one step that would turn it into the session-long alternate screen A1 rejects. A1 remains
meaningful precisely because the input box lives only on the primary screen.

**Delivered 2026-08-19.** Following really is a version check on the existing poll, and four things
this delivery settled the amendment did not say.

*Committing already sits above the gate, which is why this works at all.* `renderFrame` drains the
streaming answer into the commit log **before** it asks the turbulence gate what it may write, and
under a modal lease every permit is `NOTHING` (A12). So while the overlay holds the terminal the
render loop writes nothing and the log keeps growing — the overlay's own poll is the only writer, and
it has something new to show. Nothing had to be relaxed for this; it was already true.

*Being pinned is a position, not a mode.* `TranscriptOverlayModel.pinnedToTail` is **derived** —
the viewport is showing the last page — rather than a stored flag, so "returning to the tail resumes
following" needs no one to notice that it happened, and scrolling up needs no one to switch it off.
The growth path (`follow`) and the measurement path (`withTotalLines`) are kept apart deliberately:
measuring further into a document the reader has not reached is not new output and must not move
anything.

*"Carries no separator blank" is not "still growing", and conflating them costs a session per poll.*
The first implementation asked `isReplaySealed()` whether an item's height could still change. That
predicate answers a different question — whether a blank follows the item in [[冻结回卷]] — and the
[[提交块]] echo answers *no* to it while being final the moment it is committed. Every item in the
session was therefore re-measured on every 50 ms poll: **measured 3.1 ms per idle poll at 2000 turns**,
against **13 µs** once `isStillCommitting()` was separated out. This was found by measuring, not by
review, and the regression gate for it is a counter
(`OverlayDocumentTest.anItemThatMerelyCarriesNoSeparatorIsNotReMeasuredOnEveryPoll`).

*The overlay opens at the tail, and pays A10's measure to do it.* Opening at the top would leave live
following unreachable by default on any session long enough to matter, which is the case the amendment
exists for. The cost is the one A10 named: measured on this tree with the same harness (median, fresh
document, per-turn prompt + 24-line code block + 6-line reasoning block, width 118, page 43),

| turns / items | open at top | **open at tail** | per keystroke | idle poll | poll with output arriving |
|---|---|---|---|---|---|
| 5 / 15 | 47 µs | 62 µs | 0 µs | 13 µs | 13 µs |
| 50 / 150 | 60 µs | 698 µs | 18 µs | 12 µs | 10 µs |
| 500 / 1500 | 61 µs | 4.4 ms | 10 µs | 9 µs | 10 µs |
| 2000 / 6000 | 36 µs | 18.6 ms | 11 µs | 13 µs | 13 µs |

— one 18.6 ms measure at open, and following itself flat in session length, which is the property that
had to hold. The alternative, holding the viewport as an item anchor so the tail is reachable without
measuring, is exactly the anchor-centric positioning A5 excludes, and A10's criterion 9 is not reopened
to avoid a cost paid once per open.

Two tests were **inverted rather than kept green**, both because this amendment moves where the overlay
opens: the clipping regression now asserts the tail of an over-tall block is on screen and the top is
not, and the whole-session retention regression reaches the first turn with `g` instead of finding it
at open. Both properties are unchanged.

### A14. A pending approval closes the overlay

An approval raised while the [[转写浏览层]] holds the input thread closes the overlay, returning the
user to the primary screen where the approval card and the [[输入框]] that answers it live. The
overlay retains its scroll position so reopening resumes where the user was reading.

The alternative — rendering the approval inside the overlay — is blocked twice by decisions already
made. An [[审批应答]] is submitted through the [[输入框]] on the same key consumer as ordinary input,
and A13 makes the overlay's missing input box a boundary rather than a gap; the only other reply path,
single-key approval, was rejected because it needs a second terminal reader.

Letting the approval simply wait is worse than it looks: the worker parks on an unbounded get with no
timeout, so it stalls for as long as the user keeps reading, and from the agent's side the session
just stops with no visible cause.

*The close is driven by the queue's enqueue-time notification, and that notification had to be fixed
first.* `ApprovalPump.requestApproval` enqueued the request and notified second, so a main thread
already polling could take it — and resolve it — before the notification fired: a notification about
an approval that no longer exists. It was visible only as a flake in the announce test, because the
notification merely printed. Making it *close the overlay* turns that into a user-visible bug, so the
order is inverted: **the notification is delivered before the request becomes drainable**
(`ApprovalPumpTest.anApprovalIsNotDrainableUntilItsPendingNotificationHasBeenDelivered`). The reverse
window does not exist — nothing can take a request that has not been enqueued yet.

*The notification is counted, not flagged.* `InlineRenderer.onApprovalPending()` bumps a counter and
the overlay closes when the count differs from the one it opened on. A flag would have to be cleared
by whoever consumed the approval, and an approval rejected by ESC is never consumed by anyone — a
stale flag would then close every subsequent overlay on sight. Counting also gives the right answer to
the case the flag makes ambiguous: an overlay opened *while* an approval is already pending does not
close itself, because that approval was not raised while the user was reading.

*Nothing about the approval enters the overlay.* `TranscriptOverlay.open` takes a `BooleanSupplier`,
not a request: it cannot draw a card, because it is never given one. What it does is stop holding the
input thread; the card is then drawn by the render thread on the primary screen, through the path that
already existed. A background approval is unchanged — same notice, same Enter-to-drain — with the
close added beside it rather than instead of it, since under a fullscreen overlay that notice is
written to a screen nobody can see.

*The reading position is carried out of the modal and back into it* (`TranscriptOverlay.ReadingPosition`),
rather than stored inside it, so the overlay stays a function of what it is given. It carries a row
**and** whether that row was the tail, because at the tail the position *is* the following mode (A13):
a reader who was watching output arrive resumes watching it, not the rows that happened to be the end
when they were interrupted. The row is clamped against what the document measures now, which is what
makes a restored place safe when the log grew or the width changed while the overlay was away.

### A15. Erase-saved-scrollback is sent unconditionally; the degradation is bounded

No capability probe, no terminal allowlist. The erase is issued on every reset and terminals that
ignore it are handled by A6's honest degradation rather than by detection.

The reason a probe buys nothing is that there is no better behavior to switch to. Erasing the visible
screen is universally supported, so the screen is correct either way; only saved scrollback differs.
On a terminal that ignores the scrollback erase the symptom is therefore today's duplication
**confined to scrollback** — the visible screen, which is where users actually experience the
copies, is fixed regardless. Detecting that case would only let us choose between the same two
outcomes.

What is worth knowing is which terminals honor it, and that is a measurement, not an inference.

**Terminal.app honors it — measured 2026-08-13**, by a conformance case (`erase-saved-scrollback`)
reproduced twice with byte-identical results. A marker was scrolled 18 rows above the visible top,
the erase was issued, and the terminal's own history was read back: the marker and all 24 filler
lines were gone, while 16 lines written *after* the erase came back intact even though nine of them
were themselves in saved scrollback by capture time. That control is what makes the result mean
something — it rules out both benign explanations, that history only ever reports the visible screen,
and that the erase disables scrollback going forward rather than clearing it once.

Writing the probe surfaced a trap worth recording, because it would have inverted the answer. The
harness scrolls its own capture anchor into scrollback before a case runs, and refuses to produce a
history file when that anchor is missing — so an *honored* erase would have destroyed the anchor and
failed the capture, and the failure would have been indistinguishable from a broken harness. The
positive result would have looked like a malfunction. The case re-emits the anchor after the erase.

The probe shows that issuing the sequence clears saved scrollback; it does not prove the terminal
parsed `3J` specifically rather than clearing on some broader class of erase. That distinction does
not matter here, since the contract depends only on the observed outcome of the sequence actually
sent. Other terminals — iTerm2, kitty, alacritty, ghostty, and notably tmux, whose scrollback is its
own rather than the outer terminal's — remain **unsurveyed**; none was installed on the development
machine. A6 already covers them: they degrade honestly rather than being detected.

### A16. The offline screen model is the gate again, and the preservation tests are inverted

The gating assertion is that after a reset the screen contains the [[bounded replay window]] rendered
at the new width **and nothing else**. Terminal-specific facts — whether a terminal honors
erase-saved-scrollback, where the cursor lands — are pinned by conformance fixtures captured from
real terminals. Real-terminal storm runs are demoted from arbiter to periodic confirmation.

This reverses a hard-won position, so the reason matters. The offline model failed to arbitrate this
defect four times, and the failure was structural rather than a modelling bug: the property under
test was "the computed erase distance matches the terminal's reflow", the model's reflow rules shared
their assumptions with the code computing that distance, and a filter cannot falsify its own premise.
The destructive reset replaces that property with one that does not reference reflow at all, because
the erase makes prior screen state irrelevant. The model regains arbitration power not by improving
but because the property became checkable.

The existing preservation tests are **wrong under this contract, not merely obsolete**. The slow-drag
regressions assert that no transcript row is destroyed; A4 says rows outside the replay window are
supposed to be destroyed. They are replaced by their inverse — that exactly the replay window
survives — because keeping them would block a correct implementation. The storm script's check,
which counts copies and never verifies integrity, is likewise rewritten to assert replay-window
completeness; its blindness to integrity is what let an earlier mirage pass while it silently
destroyed transcript rows.

### A17. One ordered log, two bounded views; the erase regime is deleted, not adapted

The retained model changes identity. It stops being a five-turn *window* and becomes the ordered log
of all committed output (A8), from which two bounded views are cut: a row-budgeted view that the
reset repaints (A9), and an unbounded viewport view that the overlay browses (A10). One ordering, one
source of truth, two consumers with independent bounds.

Live-region composition loses resize entirely. Composing the frame and resetting the screen become
different responsibilities on different paths, because they now have nothing in common: composition
is incremental and geometry-stable, a reset is wholesale and geometry-triggered.

The erase-distance regime is **deleted, not adapted, and not preserved behind a flag**: the relative
erase and bottom-edge pinning, the reflow row arithmetic, the ghost-line display tracking that
existed to keep that arithmetic correct across height-only changes, the zero-delay resize frame, and
the stale-geometry retry gates whose job was to avoid writing a layout composed at a stale width —
a question the settled-geometry redraw answers directly. Keeping the old path alive as an escape
hatch is rejected: A6's non-rich fallback already is one, and a dual regime here would mean two
different answers to "where is the live region" coexisting in a codebase whose entire difficulty has
been that nobody could tell which answer was in force.

One item is a candidate rather than a conclusion. Hard-terminating every live-region row was
introduced partly as the precondition for computable erase distance; with that arithmetic gone its
load-bearing justification goes too, but it may still be earning its keep in how the display diffs.
It is to be re-justified or removed, not inherited silently. **Settled by A18: retained, on a
justification that has nothing to do with erase distance.**

### A18. Hard-terminated live rows are retained, because a logical line is the terminal's own unit

The candidate left open by A17 is closed. Terminating every [[活区]] row **stays**, and the
justification is replaced rather than repaired: it is not about erase distance, and it makes no
prediction about how any terminal re-flows.

**A logical line, not a rectangle of cells, is what a terminal hands back.** A terminal distinguishes
a row that ended because the writer ended it from a row that ended because it ran out of columns; the
second is a soft-wrap continuation, and the pair is one logical line. Selection, copy, the terminal's
own find, and its own re-flow all operate on that unit. Without a terminator JLine's `Display` moves
between rows by cursor-forwarding to the right margin and writing the *next* row's first character
there — which is precisely the terminal's encoding of "continuation". The live region therefore stops
being *n* lines and becomes one line of *n × width*, and what the user copies out of it is one line.

**The measurement that produced this, 2026-08-19.** Removal was implemented and the offline gate run,
per the issue's own method. The targeted suite (120 tests, including all 31 screen-truth regressions)
and `mvn test -Pphase16-smoke` (372 tests) were **green with hard termination removed**. That green is
the finding, not a clearance: both regimes paint **identical cells**, so `document()` — a cell grid —
cannot arbitrate this, and neither can `rowsContaining` built on it. Measured at the `Display` level
on `xterm-256color` at 40 columns with four short rows: terminated emits
`row0<CR><LF>row1<CR><LF>row2…` and yields four logical lines; unterminated emits
`row0<ESC>[6C row1<ESC>[22C row2…` and yields one.

The gate was extended to see it, by reading `historyText()` — the model's serialization of soft-wrapped
runs, i.e. what a Terminal.app copy yields — instead of the cell grid:
`ScreenTruthRegressionTest.everyLiveRegionRowIsItsOwnLineInWhatTheTerminalGivesBack` and
`aLiveRegionPaintedFromScratchIsAlsoOneLinePerRow`. Both are green as delivered and both go **red**
on removal, reporting that the dock's two rows — and, on a frame that paints the whole region at once,
the spinner row and the [[输入框]] row — came back as a single soft-wrapped line.

**This does not smuggle re-flow modelling back into the gate (A16).** The assertion is about the
region the renderer just painted, inside one [[geometry epoch]]: how many lines the terminal thinks it
was given. It computes no distance and predicts no re-flow. Which rows get stitched *is*
frame-history-dependent — it turns on what the previous frame left in the display's line model — and
that non-determinism is a second, smaller reason not to leave it to chance.

**Honestly outside the instrument.** The original presentational complaint, words torn across a fold
when the region re-flows as one unit, is **no longer observable offline**: a [[destructive resize
reset]] erases the re-flowed screen before any assertion reads it, which is why the slow-drag
regressions stayed green through removal. Whether a torn frame is visible to a user in the interval
between the terminal re-flowing and the erase being consumed is **unmeasurable from Java by
construction** — the same known-unknown A3d records for redraw latency. The decision therefore does
not rest on it. Real-terminal confirmation belongs to issue 11; what would be worth capturing there is
a copy of the live region out of Terminal.app's `history of tab` during a turn, under both regimes.

The ghost-line coupling test is **retained and restated** rather than retired, because it never
justified hard termination. Its subject is the separate rule that a `Display` is thrown away rather
than resized on a geometry change; the phantom row is the observable shape of that rule, not its
reason. Its third case is re-framed as attribution: the phantom comes from terminating the *final*
row, which is also the one terminator with no effect on line structure — so dropping it is available
and buys nothing, since `installFreshDisplay` already makes the phantom unreachable in production.

### A19. The erase is ordered screen-first, scrollback-second, because CSI 2 J *fills* the scrollback

A15 settled that the erase is sent unconditionally. It did not ask what order its three sequences go
in, and the order turned out to carry the whole contract.

**Measured 2026-08-21, Terminal.app: `CSI 2 J` does not blank the visible rows — it pushes them into
saved scrollback first.** This is the same behaviour `clear(1)` exposes, where the pre-clear screen
stays scrollable afterwards. Three independent measurements: a five-marker probe on a blank 12×40
window, where the markers came back in `history of tab` after a bare `CSI 2 J` and after
`3J 2J H`, but were destroyed by `H 2J 3J` and by `2J 3J H`; the conformance case
`erase-screen-pushes-scrollback`; and — the decisive one, because it is the product rather than a
probe — every one of eight `heightdrag 6` storm runs, each of whose histories began with **exactly one
screenful** (44 rows at 44 rows of height) ahead of the repaint.

Ordered `3J 2J H`, the reset therefore wiped the scrollback and then immediately deposited the
visible screen back into it. Normally that deposit is blank, because the [[geometry turbulence]] gate
holds the screen empty between the eager erase and the redraw — which is why seven of eight runs
looked clean while all eight were depositing. It is *not* blank when the last thing written before
the erase was a repaint, and there is exactly one way for that to happen: the **keep-alive** repaint
of A3d lands during turbulence, and the settling reset follows it ~100 ms later with no dimension
change in between to blank the screen again. One run in eight deposited a screenful of transcript
with a live spinner, [[输入框]] and dock inside it — the reported defect, reproduced with the same
history line numbers the user saw.

**This is why only the incremental storm patterns failed.** `drag` and `heightdrag` step every 50 ms,
below the 100 ms quiet interval, so a sweep never settles and crosses the 500 ms max defer instead —
they are the only patterns that ever pay a keep-alive repaint. `zoom` and `height` step every 450 ms
and `storm` every 120 ms, so each of their steps settles on its own; the authentic fullscreen gesture
is over in ~275 ms, below the max defer. Every one of those repaints is preceded by an erase that
blanked the screen, so their deposits are blank. Any account of this defect that predicts `fullscreen`
should fail is wrong.

**The order is now `2J 3J H`**: screen erase first so whatever it deposits exists, scrollback erase
behind it to destroy the deposit, cursor home last so the final position does not depend on how a
terminal chooses to implement ED(2) — a scroll of the whole screen moves the cursor on some
implementations and not on others. `H 2J 3J` (the `clear(1)` order) measured equally clean; it was
not taken because it ends on a sequence whose cursor effect is the thing in question.

**Verified where it broke**: 8 × `heightdrag 6` after the change, all clean, and — the deterministic
half of the signal, worth more than the copy counts A16 demotes — every history exactly 68 lines
against a baseline of 112 in all eight pre-fix runs. Two full `all 6` sweeps, all six patterns clean,
all six histories exactly 68 lines. The deposit is not less frequent; it is gone.

**A16's model was blind to this by construction, and now is not.** `TerminalScreenModel.eraseDisplay`
blanked rows and never pushed, so no offline assertion could see anything an erase left behind — the
gate stayed green through every version of this defect. It now models the measured behaviour (ED(2)
pushes then blanks; ED(3) clears saved lines and leaves the screen alone), which is a terminal fact of
exactly the kind A16 says belongs in the model, and carries no reflow prediction. Adding it turned
**six existing screen-truth tests red at once**, all reporting the same screenful of blanks — they had
been asserting a screen the real terminal never had. Both new facts are pinned by conformance
fixtures replayed byte-exactly against real captured Terminal.app history
(`erase-screen-pushes-scrollback`, `reset-erase-leaves-nothing`).

**Standing coverage for the defect itself** is
`ScreenTruthRegressionTest.aDragThatPaysAKeepAliveRepaintLeavesNoCopyOfItWhenItSettles`: eleven drag
steps at the measured 50 ms hand-drag cadence, so the eleventh rides out the keep-alive repaint, then
the gesture ends. It fails on the old order reporting two spinners and two docks.

**Unsurveyed, unchanged**: whether other terminals push on ED(2) at all. A6's honest degradation
covers them — a terminal that ignores `3J` keeps duplication confined to scrollback either way, and
one that does not push on `2J` is unaffected by the reordering.

### What this supersedes below

Original boundaries **1**（转写区不可变）and **2**（活动区 ≤ 可视区）now hold **within a
[[geometry epoch]] only**, not across one. Inside an epoch they are unchanged: committed rows are
never repainted, and rows scrolled past the top of the live region stay unaddressable. Across an
epoch boundary, "written out and never rewritten" is replaced by "erased and rebuilt from the
[[bounded replay window]]". Boundaries 3 and 4 are unaffected.

**Nothing is left pending measurement.** The constants of A3d and A9 and the erase-saved-scrollback
behaviour of A15 were all measured on 2026-08-13, and each is labelled in place with its sample size
and method. Two things are known-unknown rather than open: the paint half of redraw latency, which is
unobservable from Java by construction and is a perceptual judgement for the maintainer; and the
behaviour of terminals other than Terminal.app, which A6 handles by honest degradation rather than
detection. No renderer implementation has been started.

Inline 渲染当前有两处结构性问题：Ctrl+O 折叠/展开走 `printAbove` 追加式重放整轮 transcript（scrollback 里留重复内容），且只有最后一个注册块可切换；流式期间转写流、活动区 ticker、状态栏各自持锁直写 stdout，每行触发独立 flush / JLine 重绘，无帧合并。参考的 8 层终端渲染架构（retained 节点树 + cell buffer + damage diff + 帧调度）描述的是**全屏 compositor**，与 AGENTS.md「不进 alternate screen、主屏直接输出」的承诺在极端处不相容。

## 决策

采用**活动区合成器（live-region compositor）**，四条边界一次定死：

1. **转写区不可变**：写入原生 scrollback 的输出一经写出永不重绘。折叠/展开语义只在[[活动区]]与[[转写浏览层]]内成立（见 CONTEXT.md「终端渲染」段）。
2. **活动区 ≤ 可视区**：底部连续区域（流式输出、活动指示、可折叠块、输入行、状态坞）由单一渲染路径独占重绘；内容滚出活动区顶端即冻结入转写区。物理约束：主屏模式下滚出可视区的行不可寻址，任何设计不得假装能原地重绘 scrollback。
3. **保留窗口 + 转写浏览层**：内存保留最近 5 个用户回合的可重渲染模型；切换已滚出内容经全屏 overlay（alternate screen 仅用于 overlay，ESC 退出，主屏 scrollback 原样保留）。
4. **diff 引擎复用 `org.jline.utils.Display`，不自建 cell buffer、不 fork JLine**：Display 是 public API，已提供行级 diff（oldLines 对比、insert/delete-line capability 探测、行内前后缀裁剪、wcwidth、折行），等价参考架构第 4–5 层的行粒度实现。渲染线程持有自己的 Display 实例驱动活动区条带（strip），与 LineReader 的 display 栈式共存（approach A）；BSU/ESU（`ESC[?2026`）在 update 调用外层包裹，无需改 JLine 内部。

## 被否决的方案

- **全屏 compositor（自持 scrollback）**：重造滚动/复制/搜索，推翻 AGENTS.md inline 承诺与全部 `printAbove`/`Status` 集成点，回归面最大。
- **fork JLine 加第一类 strip API**：初始 3–5 周（`LineReaderImpl` ~6.5k 行折行/光标数学），且商业产品关键路径上背永久维护税（自建 artifact、每次上游 release rebase、跨终端回归矩阵）。fork 独有收益（typing 态与 streaming 态统一区域）可用 approach B 近似。
- **subclass `LineReaderImpl` 覆写 `redisplay(boolean)`（approach B）**：`redisplay` 依赖 private helper，覆写即抄 ~200 行方法体 = 单方法 mini-fork。保留为**升级路径**：仅当「用户输入期后台 push 输出」两个 Display 栈式共存出现可感知抖动时再上，A 的条带内部结构原样迁移。
- **只修视口内重绘（保守修补）**：保留 renderedRows 估算算术（当前 bug 源头），对流式延迟热点无帮助。

## 后果

- 折叠块的「frozen」从估算兜底变成架构事实：块在活动区内可原地切换，滚出后只能经转写浏览层。
- `printAbove` 从每行热路径降级为条带顶端的**冻结刷出机制**（每帧批量，非每行）。
- 自研范围收窄：参考架构第 4–5 层（cell buffer / pool / damage diff）出栈，第 3 层布局引擎（Yoga）失去必要性前提（待后续决策确认）。
- 两个 Display 栈式共存的锚定（输入行高度变化、resize）是 approach A 的固有 seam，靠单渲染线程纪律约束；此处出问题优先升级到 approach B，而非引入第三方协调机制。

---

## Appendix: the erase-regime alternatives that were falsified on real hardware

A17 deletes the erase-distance regime; this appendix records the shapes that were **tried and measured
to be worse**, so nobody re-derives one of them from first principles. All of them belong to the
retired path — none is a live option, and none has a flag.

- **A constant erase correction (a uniform `+1`) is a mirage.** All seven real-terminal patterns
  reported "clean", yet the same run permanently burned two transcript rows. `clr_eos` erases without
  pushing to scrollback, so over-erased rows are unrecoverable. `scripts/resize-storm.sh` only counted
  copies and never checked integrity, which is why it passed — and is why `check()` now also asserts
  **replay-window integrity** (each numbered transcript row exactly once).
- **Absolute bottom-edge pinning** (`cursor_address` to `rows − N + 1` → repaint the region → `clr_eos`
  downward) was worse on **every** pattern and **destroyed transcript in all of them** (`fullscreen`
  copies 99–105 → 169–177; transcript 60/60 → 41/60). The argument "painting first and erasing
  downward cannot destroy transcript" closes off upward erase only, not **in-place overwrite**. The
  positive conclusion still stands: **emitting rows downward and letting the terminal scroll**
  structurally preserves transcript, while any absolutely-positioned repaint is an overwrite.
- **`ESC 7` / `ESC 8` (DECSC/DECRC) to have the terminal remember the top of the live region.**
  Terminal.app's saved cursor **does not follow reflow**; it restores a stale screen-relative (row,
  col). Pinned by `scripts/capture-terminal-conformance.sh saved-cursor-reflow-shift`.
- **Mechanisms falsified by measurement — do not dig here again**: stale in-frame geometry (~7% of
  frames only); the live region's top being pushed into permanent scrollback (measured: region is
  always 7–9 rows against a 44–57 row screen); the renderer missing intermediate steps (209 width
  changes against 209 erase frames); a miscomputed erase distance (`(lines, up)` corresponds one-to-one
  with the width delta); the freeze path firing during the animation (0 frames hit); an extra scroll
  from the trailing newline after pinning (102 pinned frames all end in `ESC[0m`).

Two measured facts that the **current** regime depends on, kept here because they were established in
the same campaign: Terminal.app **honours** `ESC[3J` (conformance case `erase-saved-scrollback`, two
byte-identical runs), and `ESC[2J` is universal. Other terminals (iTerm2 / kitty / alacritty / ghostty
/ tmux) are **uninvestigated** and degrade honestly per A6 — a terminal that ignores `3J` keeps copies
in scrollback while the visible screen stays correct, with `CAFECLI_RENDERER=plain` as the escape
hatch.

**Reading real-terminal results.** `scripts/resize-storm.sh`'s `check()` is a chain
(`if spinner>1 … elif missing … elif duplicated`), so once it reports live-region copies it **stops
reporting** transcript duplication — `replay=60/60` means "all 60 rows are present", never "each
appears once". And the harness is noisy enough that a single run has no resolving power: the same
build produced 28–118 copies across 11 samples of `fullscreen 6`. Any real-terminal A/B must be run in
groups, and deterministic have/have-not quantities (e.g. total `history of tab` line count, which was
a flat 112 before the A19 fix and a flat 68 after, n=8 each) are worth more than copy counts.

**Forensics tooling is still present** (`ReplayForensics`, dev-only): replay a recorded trace
event-by-event for per-row attribution into permanent scrollback plus the on-screen live-region row
delta at each step.

```bash
java -cp target/test-classes:target/classes:target/cafecli-1.0-SNAPSHOT.jar \
  com.cafecli.render.inline.replay.ReplayForensics TRACE.jsonl [--all|--trail=500:520|--screen=510]
```

The deterministic replay substrate (recorder + emulator + conformance fixtures) also remains, but it
**is not an oracle for the fullscreen defect**: Java cannot observe when Terminal.app consumes PTY
bytes relative to its own reflow queue. In a paired experiment the same fixed byte sequence produced 23
copies on real hardware and 22 in the offline emulator, while the original run produced 116. See
`.scratch/deterministic-replay-oracle/PRD.md`.
