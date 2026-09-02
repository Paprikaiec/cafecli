# Native geometry sampler behind the injected seam

Status: done
Type: AFK
Delivered: 2026-08-19, all 9 criteria met, commit `6b6646c`. Idle CPU measured at 0.021 % duty
(`ThreadMXBean`, 20 s idle, n=3). Real terminal `fullscreen 6` clean 3/3 — see issue 11. Note the
native 1 ms `TIOCGWINSZ` path is **real-terminal-only**: `forSystemOutput()` returns null under
surefire, so no offline test reaches it.

## Parent

`.scratch/destructive-resize-epoch/PRD.md` — ADR 0010 amendment A3c

## What to build

Replace signal-derived geometry with **geometry sampled from the kernel**, behind the injection seam
introduced in slice 01. "Observed", in the epoch and turbulence decisions, comes to mean sampled.

The resize signal is not a faithful record of geometry changes in either direction. One paired capture
recorded 406 delivered signal markers against 284 distinct sampled transitions: signals arrive without
a new geometry, and distinct geometries are coalesced out of delivery. An epoch model built on the
signal both starts epochs that never happened and misses ones that did.

The signal is demoted to a **wake-up hint**. It arms a native terminal-size probe that polls at
roughly millisecond resolution for the duration of the [[geometry turbulence]] and parks once the
geometry settles, so idle sessions pay nothing. A slow idle heartbeat covers terminals that expose no
signal handling at all, which the renderer already tolerates.

This also removes the largest fixed cost on the write path. Frame composition currently asks the
terminal for its size, which on an exec-pty forks a subprocess — measured at 3.3 ms median, 10.7 ms
p90, about half of a frame. A sampled geometry is a field read. The standing rule that geometry must
not be re-measured immediately before writing was a consequence of that fork and retires with it.

An allocation-free native probe already exists in the replay tooling; promoting it to production use
is preferable to writing a second one.

## Acceptance criteria

- [ ] A delivered resize signal arms the sampler rather than being treated as the geometry authority
- [ ] The sampler polls at approximately millisecond resolution while geometry is changing
- [ ] The sampler parks when geometry settles, so an idle session performs no polling
- [ ] A slow idle heartbeat detects geometry changes on terminals that deliver no signal at all
- [ ] Geometry transitions that would be coalesced out of signal delivery are still observed
- [ ] No subprocess fork remains on the frame-composition write path
- [ ] Sampler behaviour is asserted through the injected geometry source, with no real terminal
- [ ] Falling back cleanly when the native probe is unavailable is asserted, with the renderer still
      functioning
- [ ] Idle CPU cost is verified as negligible with no session activity

## Blocked by

- `.scratch/destructive-resize-epoch/issues/02-turbulence-gate-eager-erase-deferred-redraw.md`
