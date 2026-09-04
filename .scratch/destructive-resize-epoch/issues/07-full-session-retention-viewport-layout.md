# Full-session retention with viewport-only overlay layout

Status: done
Type: AFK
Delivered: 2026-08-19 (ADR 0010 A10), commit `31a79bb`. `RetainedWindow` deleted; the ordered commit
log is the retained model. Measured: open 47/54/56/47 µs and per-keystroke 0/17/17/14 µs at
5/50/500/2000 turns. Accepted and recorded: jump-to-end is linear on first use per width and fold
mode (41 µs → 17.7 ms), because naming the last page without the document height needs an item
anchor, which A5 excludes. Offline only.

## Parent

`.scratch/destructive-resize-epoch/PRD.md` — ADR 0010 amendment A10

## What to build

The retained model keeps **every** turn of the running session, not the newest five, and the
[[转写浏览层]] is where a user reviews it.

This is the complement to the [[destructive resize reset]], not an unrelated feature. A policy that
treats native scrollback as disposable needs somewhere for history to live; without it, a resize is
simply amnesia. With it, the accepted cost changes shape — output outside the
[[bounded replay window]] stops being reachable through the terminal's own scroll, copy, and search,
but it is not destroyed.

Making that deliverable requires one change. The overlay currently renders the **entire** document and
then slices the viewport out of it, on every keystroke. At five turns that is invisible; at session
length it rebuilds a five-figure row list per arrow-key press. It must instead lay out only the rows
the viewport shows — accumulate heights, start emitting at the viewport start, stop after a page.

That is explicitly **not** the virtualization the decision record excludes. The withdrawn proposal
involved paging payloads from a session store, semantic-anchor positioning, and a layout cache keyed
by block revision. None of that is adopted. "Do not build ten thousand rows in order to show forty" is
a contained property of one rendering path and buys nothing beyond itself.

Memory tracks retained *source*, which is small. Rendered rows are cached per width and released
outside the working set, which is where cost would otherwise accumulate.

Retention and replay remain **different bounds serving different consumers**: retention answers to the
overlay, which costs no terminal I/O and is opened deliberately, so it is unbounded; replay answers to
the terminal, automatically, on every geometry change, so it is row-budgeted.

This history is in-memory only and dies with the process — stated deliberately, because users will
come to rely on it. `/clear` remains the one way to drop it during a session.

## Acceptance criteria

- [ ] All turns of the session are retained, with no five-turn eviction
- [ ] The overlay lays out only the rows its viewport shows, not the whole document
- [ ] Overlay open time does not grow materially with session length, asserted across a short session
      and a long one
- [ ] Scrolling cost per keystroke does not grow materially with session length
- [ ] Rendered-row caches outside the working set are released; memory tracks retained source
- [ ] `/clear` drops the retained log
- [ ] Content that falls outside the bounded replay window after a reset is still reachable in the
      overlay
- [ ] Total document height remains correct for scroll clamping, including after a thinking-toggle
- [ ] No session-store paging, semantic anchors, or revision-keyed layout cache are introduced

## Blocked by

- `.scratch/destructive-resize-epoch/issues/04-ordered-commit-log-covers-all-output.md`
