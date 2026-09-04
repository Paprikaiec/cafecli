# Turbulence gate: eager erase, single deferred redraw

Status: done
Type: AFK
Delivered: 2026-08-17, offline gate green (`TurbulenceGate` + 19 new tests), commit `6b6646c`.
Real terminal: `fullscreen 6` clean 3/3 on 2026-08-18 (`spinner=1 dock=1 replay=60/60`), after a
routing defect found by the first pass was fixed. Only `fullscreen` has been run — see issue 11.

## Parent

`.scratch/destructive-resize-epoch/PRD.md` — ADR 0010 amendment A3b, A3d

## What to build

Handle animated [[geometry turbulence]] — a native fullscreen transition or a window drag, where
dimension changes keep arriving for the length of the gesture.

The reset splits along geometry-sensitivity, and the two halves are scheduled differently:

- **The erase is geometry-independent.** Erasing the visible screen, erasing saved scrollback, and
  homing the cursor do the same thing whenever they are consumed, at any size. Written **eagerly, on
  every observed dimension change**.
- **The redraw is geometry-sensitive.** It is the only part that can be wrong. Written **exactly
  once**, when the geometry is observed to have settled.

Between the two, **every content write is gated off** — streaming answer text, spinner ticks, dock
updates, freezes. Producers keep updating the source model; nothing reaches the terminal. The screen
is deliberately blank for the duration of the turbulence.

The reason is the boundary that the replay investigation established and could not cross: Java
observes when it submits bytes, not when the terminal consumes them relative to its own reflow queue.
Gating makes queue depth one repaint *by construction* rather than asserting a timing property that
cannot be verified. Drawing a reset per event would queue one full repaint per change — on the order
of two hundred per animation — and "the last one cleans up" is precisely what the measurements refuse
to support.

Constants, measured 2026-08-13 across 5 captures (3 × fullscreen, 2 × drag) at 1 ms sampling:

| | fullscreen (authentic) | drag (synthetic, 50 ms scripted step — a floor) |
|---|---|---|
| intra-gesture gap | p50 8.8 ms, p95 33.8, p99 41.6, **max 42.8** (n=800) | p50 57.9, p99 113.6, max 117.3 (n=654) |
| gesture duration | p50 274.9 ms, **max 275.7** (n=36) | one continuous 19.0 s sweep |

- **Quiet interval 100 ms** — clears the authentic gesture's worst gap by 2.3×. It does not cover the
  synthetic drag's tail (~7 gaps per run land in 100–200 ms), which is accepted: a premature redraw is
  self-correcting, since the next change erases and repaints it, and the whole repaint is ≤12 KB.
- **Max defer 500 ms** — above the fullscreen maximum, so the authentic gesture never pays a wasted
  mid-animation repaint. It exists to bound blank time during long drags: a 19-second sweep yields
  roughly 38 keep-alive repaints rather than 328.

Both constants are configurable, and both are labelled in the decision record with their sample size.

## Acceptance criteria

- [ ] During turbulence, only erase sequences reach the terminal — no content bytes of any kind
- [ ] Exactly one redraw is written per settled gesture
- [ ] A gesture longer than the max defer produces periodic keep-alive redraws rather than an
      indefinitely blank screen
- [ ] Quiet interval defaults to 100 ms and max defer to 500 ms, both overridable
- [ ] A rapid alternating sequence of width and height changes leaves no residual rows, asserted
      against the offline screen model
- [ ] A sequence replaying the measured fullscreen gesture shape leaves no residual rows
- [ ] Timing behaviour is asserted through the injected clock, with no real waiting and no real
      terminal
- [ ] Streaming output produced during turbulence is retained in the source model and appears in the
      redraw that follows
- [ ] Text typed during turbulence is preserved and appears in the redraw
- [ ] The gate opens and closes on transitions asserted explicitly, including the max-defer path

## Blocked by

- `.scratch/destructive-resize-epoch/issues/01-destructive-reset-replaces-erase-regime.md`
