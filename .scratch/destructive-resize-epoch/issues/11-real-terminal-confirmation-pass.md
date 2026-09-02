# Real-terminal confirmation across all storm patterns

Status: ready-for-human
Type: HITL

## Parent

`.scratch/destructive-resize-epoch/PRD.md` — ADR 0010 amendment A6, A9, A15, A16

## First measurement, 2026-08-17 — FAILED 3/3

Issues 01 + 02 delivered, jar rebuilt and verified fresh, `scripts/resize-storm.sh fullscreen 6`
run three times. **Byte-identical failure every run**, so this is deterministic, not the historical
noise band:

```
fullscreen spinner=1   dock=1   replay=0/60 FAIL —— 重画界不完整：缩放毁掉了转写行 0 1 2 … 59
```

- **Good:** `spinner=1 dock=1` — the originally reported live-region-copy symptom did **not**
  reproduce. That half of the judgement passes.
- **Bad:** `replay=0/60` — *every* numbered transcript line is gone from Terminal.app history. The
  erase half runs; the replay window never lands. This is worse than the bug being fixed: the old
  regime at least let the terminal keep scrollback, so a resize now destroys the whole transcript.

Ruled out by measurement, not reasoning: harness failure (neither `取不到目标窗口的回卷` nor
`回卷里连一份活区都没有` appeared, and `spinner=1` proves the capture reached a live screen); marker
mismatch (driver emits `transcript line N ` at `RenderReproDriver.java:205`, awk matches the same at
`resize-storm.sh:532`); capture landing mid-turbulence (the script resizes then sleeps 1.2 s at
`resize-storm.sh:609`, well past the 100 ms quiet interval).

Also ruled out: `FrozenTextItem` rendering nothing — it has no `replayRows` override, but the
`StripEntry` default is `renderRetained(width)`, which does return its rows.

**Open lead.** The offline gate never exercises this path: `ScreenTruthRegressionTest` builds every
transcript through `beginAnswer()` (`AnswerSegment`), and has **zero** `printAbove` call sites, while
the driver's 60 filler lines go through `renderer.stream().println` → `printAbove` →
`FrozenTextItem`. Whether those lines reach `ReplayLog` at all in this configuration is unverified
and is the next thing to instrument — it is issue 04's territory ("ordered commit log covers all
output").

## Diagnosis and fix, 2026-08-18 — mechanism established, awaiting re-confirmation

The lead was right, and one step further along than stated: the driver's lines never reached
`printAbove` either. `InlineRenderer.emit` routed committed transcript text on
`LineReader.isReading()` — `printAbove` while the line editor was inside `readLine`, a direct
producer-thread `writeDirect` otherwise. Only the first branch reaches `LiveRegionStrip.printAbove`
and therefore `ReplayLog`, and `runResizeStormTarget` never calls `readLine` at all. Measured
offline: `liveRegion().replayLogSize()` = **0** after 20 `stream().println` calls.

Fixed in `InlineRenderer` (ADR 0010 amendment A8a): everything committed goes to the strip;
`printAbove` is kept only for the degraded configuration where a stock `LineReader` draws its own
input line, identified by `inputLineOwnedByLiveRegion` rather than by `isReading()`.

Standing coverage added:
`ScreenTruthRegressionTest.transcriptStreamedThroughTheRendererSurvivesTheFullscreenGesture` — the
streamed commit path across the measured fullscreen gesture. With the fix reverted it fails
`expected: <1> but was: <0>`, reproducing `replay=0/N` offline.

## Re-check, 2026-08-18 — `fullscreen` clean 3/3

Jar rebuilt and verified fresh, `scripts/resize-storm.sh fullscreen 6` run three times. Exit 0 every
time, byte-identical:

```
fullscreen spinner=1   dock=1   replay=60/60 ok
```

- `replay=` went from a deterministic **0/60 failing 3/3** to **60/60 passing 3/3**. Because the
  baseline was deterministic rather than inside the 28–118 noise band, a three-run sample
  discriminates here.
- `spinner=1 dock=1` held — no regression in the half that already passed.

**Measured:** the baseline failure no longer reproduces in 3 runs. **Inferred:** consistent with the
`InlineRenderer.emit` routing defect having been the cause. This is *not* the completed confirmation
pass — only `fullscreen` was run. The remaining six patterns, the streaming-turn and overlay
variants, and the ×3 budget judgement below are all still outstanding.

## Re-check after issue 03, 2026-08-19 — `fullscreen` clean 3/3

Jar rebuilt and verified fresh, `fullscreen 6` ×3, exit 0 each: `spinner=1 dock=1 replay=60/60 ok`.

This run existed for one specific reason: issue 03's native **1 ms `TIOCGWINSZ` poll loop is
real-terminal-only by construction** — `NativeTerminalSizeProbe.forSystemOutput()` returns null under
surefire, so no offline test can reach it. The offline suite being green says nothing about it.

- Duration 27.90 / 28.03 / 28.03 s. **~28 s is the normal runtime**, not a deviation: the script
  calls `close_target` right after capture instead of waiting out the 60 s `--storm` lifetime.
- Driver CPU (1 Hz `ps`, runs 2–3): peaks 84–92 % during JVM startup, then 0.3–8.6 %. **Inferred
  only** — macOS `ps %cpu` is a decaying average and smooths a burst confined to a ~275 ms gesture.
  What it does rule out is a loop spinning *outside* gestures.
- The earlier empty `--storm=60` husk window is gone; the cleanup trap fired all three runs.

**Measured:** no defect in 3 `fullscreen` runs. **Not** verified — one pattern of seven.

## Grouped sweep, 2026-08-22 — 30/30 clean at `9fc305c`

Five × `scripts/resize-storm.sh all 6` against a freshly rebuilt jar, ~127 s per sweep (tight:
126–127 s). **Every pattern clean in every sweep** — `zoom` / `drag` / `height` / `heightdrag` /
`fullscreen` / `storm` all 5/5, `spinner=1 dock=1 replay=60/60`, exit 0. No harness-inconclusive
runs, no `回卷中命中的行` block anywhere.

This is the **first grouped sweep whose reporting can be trusted**: it postdates both instrument
fixes (`capture_history` no longer slices away a deposit that lost its anchor row, and `check()` is
no longer an `elif` chain that let a live-region failure mask a duplicate-transcript failure in the
same run), and it postdates the corrected `ED(2)` model.

**How much this constrains the two regressed patterns, inferred from the pre-fix baseline:**
`heightdrag` failed 2 of 3 sweeps before the fix, so if that rate still held, 5 clean sweeps has
probability ≈ 0.33⁵ ≈ **0.4%** — real evidence the fix changed behaviour. `drag` failed 1 of 3, so
5 clean sweeps has probability ≈ 0.67⁵ ≈ **13.5%** — `drag` is much the less constrained of the two,
and is the one to re-run if any doubt remains.

**Measured:** the defect did not reproduce in 5 sweeps × 6 patterns. **Not** established: that the
pattern set is verified (a sample of 5), or anything about terminals other than Terminal.app.

## Manual verification by the user, 2026-08-22

Reported after using Cafecli directly (not via the harness): **resize during streaming output is
basically fine**, and **`Ctrl+O` behaves as expected**. That covers criterion 7 and the general
health of the [[转写浏览层]].

Deliberately *not* claimed from this: the ×3 budget perceptual judgement, the overlay held open
*across a resize* specifically, and an approval raised while the overlay is open. Those were not
named in the report and remain open below.

## Manual verification by the user, 2026-08-28 — the [[输入框]] itself, three defects

First human pass over the resident input box (ADR 0016 D6 / `.scratch/always-live-input-box/issues/09`).
**Three defects reported, all fixed, each reproduced offline first and each confirmed to turn its new
criterion red when the fix is reverted.** They are recorded here too because two of them are
*geometry* defects and this is the pass that owns geometry on real hardware:

1. **The wash ran one column past the rules.** The rules were a flat `width - 1`; a row the line editor
   fills is `width`. Fixed by sizing the rules from the widest visible row.
2. **Backspacing across wrapped CJK rows left the old rows on screen.** A filled row puts the cursor at
   the right margin, and JLine reaches that margin by re-printing the character in column `width - 1`;
   with a double-width character there, the re-print overflows and desynchronises JLine's cursor model
   from the screen by two columns for every subsequent frame. Fixed by clamping the composed cursor out
   of the last column. **ASCII does not reproduce this** — that asymmetry is part of the criterion.
3. **No key inserted a newline** (Shift+Enter printed its escape sequence as text). Bindings added.

Deliberately *not* claimed from this round: the empty box reading as a waiting field (D6's primary
claim), submission continuity, the right prompt's three states and its behaviour as text approaches it,
the short-terminal ladder — **including whether 20/12 are the right thresholds, which are still picked
rather than measured** — and the slow column-by-column width drag. Those five were not exercised. The
three that were exercised need re-running now that they are fixed.

## Still needs a human — the harness cannot reach these

`RenderReproDriver --storm` exercises none of the following, so they are outside any scripted sweep:

1. The perceptual judgement on the ×3 replay budget — is the restored screenful the right amount of
   history to a human eye (criterion 6).
2. A resize during an **actively streaming turn** (criterion 7).
3. The [[转写浏览层]] **held open across a resize** (criterion 8).
4. An **approval raised while the overlay is open** (criterion 9) — overlay closes itself, card and
   [[输入框]] usable on the primary screen, reopening lands on the same page.

> ⚠️ **Do `.scratch/always-live-input-box/issues/12-session-sigint-guard.md` before this pass, or know
> the landmine** (ADR 0016 D3, filed 2026-08-26). `enterRawMode()` does not clear `ISIG` and this
> repository installs **no `Signal.INT` handler anywhere**, so pressing Ctrl+C while the overlay is
> open — which items 3 and 4 above invite a tester to do — kills the process and takes the session,
> its history, and its in-memory 任务 with it. Issue 12 is unblocked and small.
>
> **ADR 0016 also adds four things this checklist predates**, none of which are implemented yet, so
> they belong to a *later* confirmation pass rather than this one: view-owned render caches (D1, the
> overlay and the render thread currently share mutable blocks), the input box composing zero rows on
> a width-mismatched capture (D4), source-span log positions (D5), and the input box's wash (D6).
> When those land, items 3 and 4 here need re-running rather than inheriting this pass's result.

## What to build

The confirmation pass on a real terminal, after the offline gate is green. This is the only slice that
requires a human, for two reasons that no agent can discharge.

**Copy counts have no single-run discriminating power.** The same gesture on unchanged code has
produced anywhere from 28 to 118 residual rows. Any real-terminal comparison needs paired batches, and
someone has to judge whether a difference is signal or noise band.

**The paint half of redraw latency is unobservable from Java by construction.** Render cost and byte
volume are measured — 218 µs and 7.5 KB at the default ×3 budget on a 44-row terminal — but how the
terminal *paints* those rows, and whether the resulting settle feels laggy, is a perceptual judgement.
The multiplier is chosen for how much scrollback the user keeps, and confirming ×3 means resizing and
looking.

Run all seven storm patterns, not a subset. Covering only the stepwise patterns is exactly the mistake
that once let a fullscreen-only defect be declared fixed: they are stepwise and have no falsifying
power over animated resize. The authentic gesture is the fullscreen animation.

Verify **both** properties every time. Residual rows alone is the check that let an earlier fix pass
while it silently destroyed transcript rows; replay-window completeness must be checked alongside.

## Acceptance criteria

- [ ] All seven storm patterns run, including the fullscreen animation and the single-column drag
- [ ] Runs are paired batches, not single runs, with the noise band accounted for in the conclusion
- [ ] No residual live-region rows remain after any pattern
- [ ] Replay-window completeness is verified after each pattern, not only residual-row count
- [ ] No content that should have survived is missing — the failure mode of the earlier mirage fix
- [ ] The settle after a resize is judged acceptable at the default ×3 budget, or the multiplier is
      adjusted and the decision record updated with the reasoning
- [ ] Behaviour is confirmed with a turn actively streaming during the resize
- [ ] Behaviour is confirmed with the overlay open across a resize
- [ ] Behaviour is confirmed with an approval raised while the overlay is open (issue 09 / A14): the
      overlay closes on its own, the card and the [[输入框]] are usable on the primary screen, and
      reopening after answering lands back on the same page
- [ ] Terminal.app's erase-saved-scrollback conformance fixture still passes
- [ ] Any terminal other than Terminal.app that happens to be available is noted with its observed
      behaviour, without blocking on a survey
- [ ] Repository known-boundaries documentation is updated to reflect the resolved state of the
      long-standing resize defect

## Blocked by

- `.scratch/destructive-resize-epoch/issues/03-native-geometry-sampler.md`
- `.scratch/destructive-resize-epoch/issues/05-freeze-boundary-in-source-coordinates.md`
- `.scratch/destructive-resize-epoch/issues/09-approval-closes-the-overlay.md`
- `.scratch/destructive-resize-epoch/issues/10-hard-terminated-rows-re-justify-or-remove.md`
