# PRD: Destructive resize epoch — resize becomes a clean slate

Status: ready-for-agent
Date: 2026-08-16
Branch: TBD (current work sits on `feat/always-live-input-box`)
Decision record: ADR 0010 amendment A1–A17 (replaces the withdrawn R1–R7 alternate-screen reopening);
respects ADR 0014 (single terminal writer, line editor yields the brush), ADR 0005 (event loop,
approval pump), ADR 0011 (typed block content model), ADR 0013 (overlay collapses thinking, Ctrl+T)

## Problem Statement

Resizing the terminal corrupts the display. Entering native fullscreen and coming back, or dragging
the window edge, leaves duplicated spinner and status-dock fragments smeared down the screen; a
single gesture can leave dozens of copies. Once they scroll into [[冻结回卷]] they are permanent —
the session looks broken for as long as it lasts, and the user cannot tell which rows are real.

Worse, the user cannot predict it. The same gesture on the same build produces anywhere from 28 to
118 copies, so a session may look fine for an hour and then degrade after one careless window drag.

The underlying cause has resisted four rounds of fixes. Every one of them tried to compute how far
to erase by modelling how the terminal reflowed the [[活区]] — and each fix was validated by a screen
model that shares its assumptions with the code being tested, so the test suite went green while the
real terminal stayed broken. One accepted fix even destroyed transcript rows to hide copies, because
the regression script counted copies and never checked integrity.

## Solution

Resize stops being a repair problem and becomes a clean slate.

A geometry change ends a [[geometry epoch]]. Cafecli erases the visible screen **and** the terminal's
saved scrollback, then repaints a bounded window of recent content at the new size. It never needs to
know where the terminal moved anything, because nothing from the previous epoch is trusted to
survive.

The user pays a real, stated price: terminal scrollback does not survive a resize. That includes
shell history from before Cafecli started. In exchange the display is always correct, and it is
correct by construction rather than by arithmetic that has been wrong four times.

Session history moves somewhere it can be trusted. The [[转写浏览层]] now retains the **whole**
session rather than five turns, opens at any time including mid-turn, and follows new output live.
Output that scrolls out of the terminal is still there — it is reached with `Ctrl+O` instead of the
terminal's own scrollbar.

## User Stories

1. As a Cafecli user, I want the screen to be correct after I resize the window, so that I can trust
   what I am reading.
2. As a user who enters native fullscreen and returns, I want no duplicated spinner or status rows,
   so that the session does not look broken.
3. As a user who drags the window edge slowly across many columns, I want no accumulated copies, so
   that repeated small resizes are as safe as one large one.
4. As a user who only changes the window height, I want the display to stay correct, so that a
   vertical resize is not silently corrupting the next horizontal one.
5. As a user who only changes the width, I want the display to stay correct, so that the common case
   of widening a pane is safe.
6. As a user resizing during an active turn, I want the streaming answer to continue correctly
   afterwards, so that a resize never truncates or duplicates the answer I am reading.
7. As a user, I want the screen to go blank while I am actively dragging rather than showing mangled
   half-reflowed content, so that I can tell the difference between "settling" and "broken".
8. As a user, I want the screen to come back promptly once I stop dragging, so that the blank period
   reads as part of the gesture and not as a hang.
9. As a user performing a long, slow drag, I want the screen to come back periodically rather than
   staying blank for the whole gesture, so that I am never staring at an empty terminal.
10. As a user, I want recent output repainted after a resize, so that I do not lose my place in what
    I was reading.
11. As a user, I want enough repainted that I can still scroll up a screen or two afterwards, so that
    the terminal's own scrollback is not left empty.
12. As a user, I want to be told plainly that resizing discards scrollback, so that I am not
    surprised when my shell history from before Cafecli disappears.
13. As a user on a terminal that ignores erase-saved-scrollback, I want honest degradation rather
    than a false promise, so that I can choose the plain renderer if it matters to me.
14. As a user, I want the startup banner, slash-command output, and MCP and Skill summaries to
    survive a resize, so that output I asked for is not destroyed by an unrelated window action.
15. As a user with a background task, I want its completion notice to survive a resize, so that I
    never miss learning that a task finished.
16. As a user, I want to press `Ctrl+O` at any time, including while a turn is running, so that I can
    check what a tool returned without waiting for the turn to end.
17. As a user browsing the overlay during a turn, I want new output to appear as it arrives, so that
    the overlay shows what is happening now rather than a stale snapshot.
18. As a user who has scrolled up in the overlay, I want my position held while new output arrives,
    so that reading is not yanked out from under me.
19. As a user at the tail of the overlay, I want it to follow new output, so that I can watch a turn
    progress.
20. As a user, I want the overlay to show the whole session rather than the last five turns, so that
    I can review anything that happened since I started.
21. As a user, I want the overlay to open instantly regardless of how long the session has run, so
    that full history does not make the feature unusable.
22. As a user who resizes while the overlay is open, I want the overlay to redraw itself correctly,
    so that browsing history survives a window change.
23. As a user returning from the overlay after resizing, I want the main screen repainted correctly,
    so that leaving the overlay does not reintroduce the corruption.
24. As a user, I want an approval request to bring me back to the main screen automatically, so that
    a tool never waits indefinitely while I read history.
25. As a user returning to the overlay after answering an approval, I want my reading position
    restored, so that the interruption costs me nothing.
26. As a user, I want `ESC` in the overlay to close the overlay rather than cancel my turn, so that
    browsing is never destructive.
27. As a user, I want to know that cancelling a turn means closing the overlay first, so that the key
    behaves predictably.
28. As a user of the slash palette, I want a resize during it to be handled cleanly, so that every
    modal behaves the same way.
29. As a user, I want `/clear` to remain the way I drop session history, so that there is one
    predictable way to start fresh.
30. As a user, I want the input box to keep working normally through all of this, so that resize
    handling never costs me the ability to type.
31. As a user, I want my typed but unsubmitted text preserved across a resize, so that a window
    change never loses my draft.
32. As a maintainer, I want the offline screen model to gate this behaviour, so that regressions are
    caught before a real-terminal run.
33. As a maintainer, I want the gating assertion to be "the screen contains the replay window and
    nothing else", so that the test does not depend on reflow modelling that has been wrong before.
34. As a maintainer, I want the old preservation tests replaced rather than repaired, so that tests
    asserting scrollback survives do not block a contract that deliberately destroys it.
35. As a maintainer, I want the storm script to verify replay-window completeness rather than only
    counting copies, so that a fix cannot pass by destroying transcript rows.
36. As a maintainer, I want the quiet interval and max defer testable without a real terminal, so
    that timing regressions do not depend on a noisy manual run.
37. As a maintainer, I want output coverage asserted against the commit log rather than emitted
    bytes, so that "did this reach the replay model" is a direct question.
38. As a maintainer, I want the row budget testable as a pure function, so that block-quantization
    overshoot is pinned explicitly.
39. As a maintainer, I want the erase-distance regime deleted rather than left dormant, so that two
    competing answers to "where is the live region" cannot coexist.
40. As a maintainer, I want terminal-specific behaviour pinned by conformance fixtures, so that
    assumptions about a terminal are measured rather than argued.

## Implementation Decisions

### The reset

- Cafecli stays on the primary screen. A session-long alternate screen is rejected. The overlay's
  short-lived alternate-screen use is unaffected.
- Within a [[geometry epoch]], behaviour is unchanged: completed output enters native scrollback,
  is never repainted, and the terminal owns scrolling, copy, and search.
- A geometry change performs a [[destructive resize reset]]: erase visible screen, erase saved
  scrollback, repaint the [[bounded replay window]] plus current interactive state at the new size.
- **Every** observed dimension change ends the epoch — width and height alike, no reflow-relevant
  subset. A height-only change was measured shifting the cursor by one row via a ghost line, which is
  what made each subsequent width change erase one row short.

### Turbulence scheduling

- The reset splits along geometry-sensitivity. The erase (screen + saved scrollback + home) is
  geometry-independent and is written **eagerly on every change**. The redraw is geometry-sensitive
  and is written **exactly once**, when geometry settles.
- Between the two, **all** content writes are gated off — streaming text, spinner ticks, dock
  updates, freezes. Producers keep filling the source model; nothing reaches the terminal. The screen
  is deliberately blank for the duration of [[geometry turbulence]].
- Rationale: Java observes when it submits bytes, not when the terminal consumes them relative to its
  reflow queue. Gating makes queue depth one repaint *by construction* rather than asserting a timing
  property that cannot be verified.
- The zero-delay resize frame retires with the arithmetic it protected. Bypassing frame cadence was
  mandatory only because erase distance was computed relative to the previous frame's width.

### Geometry observation

- Geometry is **sampled from the kernel**, not delivered by signal. One paired capture recorded 406
  delivered WINCH markers against 284 distinct sampled transitions — signals fire without a new
  geometry, and distinct geometries coalesce out of delivery.
- The signal is demoted to a wake-up hint: it arms a native size probe that polls at roughly
  millisecond resolution for the duration of turbulence and parks when geometry settles. A slow idle
  heartbeat covers terminals exposing no signal handling.
- This also removes the largest fixed cost on the write path. Frame composition currently forks a
  subprocess to ask the terminal its size — measured at 3.3 ms median, 10.7 ms p90, about half a
  frame. A sampled geometry is a field read.

### Measured constants

Turbulence, measured 2026-08-13 across 5 captures (3 × `fullscreen 6`, 2 × `drag 4`), 1 ms sampler:

| | fullscreen (authentic) | drag (synthetic) |
|---|---|---|
| intra-gesture gap | p50 8.8 ms, p95 33.8, p99 41.6, **max 42.8** (n=800) | p50 57.9, p99 113.6, max 117.3 (n=654) |
| gesture duration | p50 274.9 ms, **max 275.7** (n=36) | one continuous 19.0 s sweep |
| changes per gesture | 21–25 | 328 |

- **Quiet interval: 100 ms.** Clears the authentic gesture's worst gap by 2.3×. It does not cover the
  synthetic drag's tail (~7 gaps per run in 100–200 ms), which is accepted: a premature redraw is
  self-correcting, and the whole repaint is ≤12 KB.
- **Max defer: 500 ms.** Above the fullscreen maximum, so the authentic gesture never pays a wasted
  mid-animation repaint. It bounds blank time during long drags instead.
- The drag figures are a **floor**, not a human drag — the script steps on a scripted 50 ms sleep.

### The replay window

- The window captures **every** output reaching [[冻结回卷]], as ordered items in write order: typed
  blocks and generic streamed text interleaved. Live-region content is excluded by definition, not by
  policy — spinner, approval cards, [[输入框]] and dock are never committed.
- Bounded by a **row budget in screenfuls** (default ×3), rendered backwards from the newest until
  the budget fills. Overflow scrolls into the freshly erased scrollback.
- Retention and replay are **different bounds serving different consumers**. Retention answers to the
  overlay, which costs no terminal I/O and is opened deliberately. Replay answers to the terminal,
  automatically, on every geometry change.

Redraw cost, measured 2026-08-13, cold path (cache released per pass, 120 fresh widths, 33 blocks /
717 rows), at a 44-row height:

| multiplier | budget | render p50 | repaint volume |
|---|---|---|---|
| ×1 | 44 rows | 183 µs | 2.5 KB |
| ×2 | 88 rows | 185 µs | 5.0 KB |
| ×3 | 132 rows | 218 µs | 7.5 KB |
| ×5 | 220 rows | 313 µs | 12.4 KB |

- **Render cost does not constrain the multiplier** — one screenful to five costs ~130 µs and 10 KB.
  The multiplier is chosen for how much scrollback the user keeps, not for cost. The remaining cost is
  the terminal's paint, which Java cannot observe.
- **The budget is advisory**, because whole blocks render: a 44-row budget produced 104 rows, a
  132-row budget produced 207. Block granularity decides real repaint size. Either accept overshoot or
  render the oldest block partially.

### Freeze boundaries

- Every position dividing "already committed" from "still live" is expressed in **source**
  coordinates, never rendered or styled-line coordinates, and re-derived at the current width.
- The streaming answer forces this: it projects per width already, but tracks commitment as a count of
  *styled* lines, and the same source at a different width yields a different styled-line count.
  Carried across a width change that count empties the live tail or repeats committed content.
- Freeze boundaries are **per epoch** — a new epoch re-derives rather than inherits.

### Retention and the overlay

- Retention is the **whole session**, not five turns. Memory tracks retained source, which is small;
  rendered rows are cached per width and released outside the working set.
- The overlay lays out **only its viewport** instead of rendering the whole document and slicing. This
  is not the withdrawn virtualization proposal — no session-store paging, no semantic anchors, no
  revision-keyed layout cache.
- The overlay opens at any time including mid-turn, and **follows live**: pinned at the tail it
  advances, scrolled up it holds position. The previous rejection of live-follow rested on two
  premises now false — whole-document re-render per frame (removed by viewport layout) and a shared
  block-level lock (the retained model is already synchronized).
- History is **in-memory only and dies with the process**. Stated deliberately, because users will
  come to rely on it.
- The overlay's **absent [[输入框]] is a boundary, not an omission**. Full history plus live-follow
  plus self-managed resize is most of a full-screen compositor; adding an input box is the step that
  would turn it into the rejected session-long alternate screen.

### Modals and approvals

- A modal **leases** the terminal: while it is open the render loop writes nothing, and a geometry
  change is recorded but its reset is **deferred** — exactly one fires on return if geometry changed,
  none if it did not. The modal handles its own resize internally.
- This closes a defect predating the amendment: a resize posts a frame regardless of idleness, so
  resizing during an open overlay already paints live-region rows over it.
- A pending [[审批应答]] **closes the overlay**, returning the user to the input box that answers it,
  with scroll position retained. Rendering the approval inside the overlay is blocked twice over — an
  approval reply goes through the input box on the single key consumer, and single-key approval was
  already rejected as needing a second terminal reader. Letting it wait is worse: the worker parks
  with no timeout.

### Terminal support

- Erase-saved-scrollback is sent **unconditionally** — no probe, no allowlist. A probe buys nothing:
  erasing the visible screen is universal, so only saved scrollback differs, and on a terminal that
  ignores it the symptom is today's duplication confined to scrollback while the visible screen is
  correct regardless.
- **Terminal.app honors it — measured 2026-08-13**, reproduced twice byte-identically. A marker
  scrolled 18 rows above the visible top vanished along with 24 filler lines, while 16 lines written
  after the erase returned intact even though nine were themselves in saved scrollback by capture
  time. That control rules out both benign explanations.
- Other terminals are unsurveyed; honest degradation covers them, with the plain renderer as the
  explicit escape hatch.

### Structure

- The retained model changes identity: it stops being a five-turn *window* and becomes the ordered
  **log** of all committed output, from which two bounded views are cut.
- Live-region composition **loses resize entirely**. Composing a frame and resetting the screen become
  different responsibilities: composition is incremental and geometry-stable, a reset is wholesale and
  geometry-triggered.
- The erase-distance regime is **deleted, not adapted, and not preserved behind a flag**: relative
  erase and bottom-edge pinning, reflow row arithmetic, ghost-line display tracking, the zero-delay
  resize frame, and the stale-geometry retry gates. A dual regime would mean two answers to "where is
  the live region" coexisting in a codebase whose entire difficulty has been not knowing which was in
  force. The plain renderer is already the escape hatch.
- Hard-terminating every live-region row is a **candidate for removal**, not a conclusion: it was
  introduced partly as the precondition for computable erase distance. Re-justify or remove it; do not
  inherit it silently.

## Testing Decisions

A good test here asserts **what the user sees**, not how bytes were produced. The four failed rounds
all passed tests that asserted emitted sequences or erase arithmetic; the assertion that would have
caught them is the document the terminal ends up displaying. Tests must not reference erase distances,
cursor arithmetic, or frame internals.

### The gating assertion

After a reset, the screen contains the bounded replay window rendered at the new width **and nothing
else**. This is why the offline model regains arbitration power: it no longer references reflow, which
is where its assumptions were shared with the code under test.

### Existing seams, preferred

- **Screen-truth regression rig** — builds the renderer on a tracing terminal with a manual frame
  ticker, records byte offsets at each resize, and replays the whole stream through a fresh screen
  model with resizes applied in order. This is the primary gate. Prior art: the existing slow-drag
  regressions, including the variant with the input box bound.
- **Terminal conformance fixtures** — pin terminal behaviour against captured real-terminal history.
  Prior art: the eight existing cases, now including erase-saved-scrollback.
- **Transcript overlay model** — already a pure state/action/reduce machine. Live-follow and the
  follow-versus-browse distinction are asserted here, not through the IO loop. Prior art: existing
  overlay navigation and Ctrl+T toggle tests.
- **Approval pump's pending notifier** — the existing callback seam for "approval closes the overlay".
- **Repro driver** — realistic turn content with no LLM.

### New seams

1. **Injected geometry source and clock.** The turbulence state machine takes a geometry supplier and
   a time source rather than reaching for the terminal and the system clock, following the existing
   manual-frame-ticker precedent. Makes quiet interval, max defer, gate-open/gate-close, and the
   parked-sampler transition assertable with no terminal.
2. **The ordered commit log as an assertable model.** Output coverage is asserted against the log —
   "did the banner, slash output, background push enter it, in this order" — rather than against
   bytes.
3. **The row-budgeted view as a pure function.** Given blocks, width, and budget it returns the items
   and rows selected, making block-quantization overshoot explicit.

### Replaced tests

The slow-drag preservation regressions assert that no transcript row is destroyed. Under this
contract rows outside the replay window are *supposed* to be destroyed, so they are **wrong, not
obsolete**, and are replaced by their inverse: exactly the replay window survives. The storm script's
check is rewritten to assert replay-window completeness — its blindness to integrity is what let an
earlier fix pass while destroying transcript rows.

### Coverage to add

Height-only, width-only, and alternating height/width step sequences; resize mid-stream during an
answer; resize with a modal open and the deferred reset on return; approval during an open overlay;
overlay open across a resize; the freeze boundary surviving a width change.

## Out of Scope

- Full-session recovery after shutdown or crash.
- A durable semantic transcript journal as authoritative session record.
- Coupling the renderer to the Runtime API event substrate.
- Virtualized full-screen history with an anchor-centric layout engine, session-store paging, or a
  revision-keyed layout cache.
- Exit-time transcript reconstruction.
- A session-long alternate screen, and any input box inside the overlay.
- Surveying terminals other than Terminal.app — none were installed; honest degradation covers them.
- Measuring the paint half of redraw latency: unobservable from Java by construction. Choosing the
  multiplier above ×3 is a perceptual judgement for the maintainer.
- A terminal acknowledgement fence (DSR round-trip) to release the redraw. It is the known way to
  observe the consumption side directly, but needs a second terminal reader, which is forbidden and
  documented as a deadlock bug. It remains the upgrade path if timing proves unstable.

## Further Notes

**A trap that would have inverted the conformance answer.** The conformance harness scrolls its own
capture anchor into scrollback before a case runs and refuses to produce history when the anchor is
missing. An *honored* erase therefore destroys the anchor and fails the capture — and that failure is
indistinguishable from a broken harness, so the positive result looks like a malfunction. The case
re-emits the anchor after the erase. Any future scrollback-affecting probe faces the same trap.

**Real-terminal copy counts have no single-run discriminating power.** The same gesture on unchanged
code has produced 28 to 118 copies. Any real-terminal A/B needs paired batches. This is why the
offline gate matters and why real-terminal runs are confirmation rather than arbiter.

**The offline model failed to arbitrate this defect four times.** That history is not a reason to
distrust it here, but the reason must be understood: the property under test changed. Any future
change that reintroduces reflow modelling into the gating assertion reintroduces the trap.

**Documentation sync is part of delivery.** The repository's inline-rendering conventions and its
known-boundaries section currently describe the erase-arithmetic regime accurately, because that is
still what the code does. They change when the code does, per the repository's rule that behaviour
changes require documentation updates.
