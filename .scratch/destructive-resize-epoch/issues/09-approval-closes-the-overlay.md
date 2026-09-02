# A pending approval closes the overlay

Status: done
Type: AFK

## Parent

`.scratch/destructive-resize-epoch/PRD.md` — ADR 0010 amendment A14

## What to build

An approval raised while the [[转写浏览层]] holds the input thread **closes the overlay**, returning
the user to the primary screen where the approval card and the [[输入框]] that answers it live. The
overlay retains its scroll position so reopening resumes where the user was reading.

This becomes reachable only once the overlay is available during an active turn, which is when
approvals actually occur. The approval queue already exposes a notification callback fired at enqueue
time; that is the seam to hang this on.

Letting the approval simply wait is worse than it looks. The worker parks on a get with **no
timeout**, so it stalls for as long as the user keeps reading, and from the agent's side the session
just stops with no visible cause.

Rendering the approval inside the overlay is blocked twice over by decisions already made. An
[[审批应答]] is submitted through the input box on the same key consumer as ordinary input, and the
overlay's missing input box is a deliberate boundary rather than a gap; the only other reply path,
single-key approval, was already rejected because it needs a second terminal reader.

## Acceptance criteria

- [x] An approval raised while the overlay is open closes the overlay
- [x] The user lands on the primary screen with the approval card visible and the input box ready
- [x] The overlay's scroll position is retained and restored on reopen
- [x] The waiting worker is unblocked within a repaint rather than waiting for the user to close
      manually
- [x] A background approval retains its existing notification and drain behaviour
- [x] No approval UI, prompt, or key handling is added inside the overlay
- [x] Closing is driven by the existing enqueue-time notification seam, asserted without a real
      terminal
- [x] Answering the approval and reopening the overlay shows the same position and the newer content

## Blocked by

- `.scratch/destructive-resize-epoch/issues/06-modal-lease-and-deferred-reset.md`
- `.scratch/destructive-resize-epoch/issues/08-overlay-always-available-live-follow.md`
