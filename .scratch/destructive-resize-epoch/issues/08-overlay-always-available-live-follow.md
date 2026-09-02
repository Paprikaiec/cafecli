# Overlay always available and following live

Status: done
Type: AFK

## Parent

`.scratch/destructive-resize-epoch/PRD.md` — ADR 0010 amendment A13

## What to build

The [[转写浏览层]] opens at any time, including during an active turn, and follows new output while
open: pinned at the tail it advances, scrolled up it holds position so browsing is never yanked.

The glossary has stated "空闲与回合运行中都可进入" all along; the key binding gates on idle and prints
a hint instead. This makes the code match the documented intent rather than changing direction.
Mid-turn access is also the case that makes liveness matter — the reason to open it during a turn is
to see what is happening now.

The previous rejection of live following rested on two premises, **both now false**: that it would
require re-rendering the whole retained window every frame, which the viewport-only layout removes,
and that it would require sharing a block-level lock between the input and render threads, which the
retained model's existing synchronization already removes. The overlay's key read already polls with a
timeout, so following is a version check on that existing loop.

Two consequences are accepted knowingly. `ESC` inside the overlay closes the overlay and does **not**
cancel the turn, so cancelling during browsing means closing first. And the overlay's line-scroll
replaces the terminal's own scroll, copy, and search for as long as it is open.

**The absent [[输入框]] is a boundary, not an omission.** Full session history, live following, and
self-managed resize together are most of a full-screen compositor; adding an input box to the overlay
is the one step that would turn it into the session-long alternate screen that was rejected. Do not
add one.

## Acceptance criteria

- [x] The overlay opens during an active turn, with no availability hint and no idle gate
- [x] The idle-only gate and its hint text are removed
- [x] New output appears in the overlay while it is open, without reopening
- [x] Pinned at the tail, the viewport advances as output arrives
- [x] Scrolled up, the viewport holds position while output arrives
- [x] Returning to the tail resumes following
- [x] `ESC` closes the overlay and does not cancel the running turn
- [x] `Ctrl+T` continues to toggle all thinking blocks, with document height re-measured
- [x] Following is asserted through the pure overlay model, not the IO loop
- [x] No input box, prompt, or text entry is added to the overlay
- [x] The glossary and repository documentation match the delivered behaviour

## Blocked by

- `.scratch/destructive-resize-epoch/issues/07-full-session-retention-viewport-layout.md`
