# Freeze boundary in source coordinates

Status: done
Type: AFK
Delivered: 2026-08-19 (ADR 0010 amendment A11, "Delivered" section). Three measured findings sharpen
the defect: it is **tables only** (prose folds outside the styled-line count, and two-column tables
degrade to a width-independent form — the first failing test was green by accident); the break is
**one-directional**, with widening the real defect (content streamed after the resize is invisible
for good, not a tail glitch); and the boundary may only land where markdown has flushed, which is why
the retroactive attribution of deferred output had to be deleted. Offline only — not confirmed on a
real terminal.

## Parent

`.scratch/destructive-resize-epoch/PRD.md` — ADR 0010 amendment A11

## What to build

Every position that divides "already committed" from "still live" is expressed in **source**
coordinates, never in rendered or styled-line coordinates, and is re-derived at the current width.

The streaming answer is the case that forces this. It already projects itself per width and memoizes
each projection, but it tracks how much has been committed as a single count of *styled* lines — and
the same source rendered at a different width yields a different number of styled lines, because
markdown reflows and tables re-allocate their columns. Carried across a width change, that count
under-counts and silently empties the live tail, or over-counts and repeats committed content.

The projection already records a per-source-line cumulative styled count, so the boundary can be held
in source space and mapped into whichever projection is current. The fix is latent in the existing
structure rather than requiring a new one.

Under the previous contract this was a small tail glitch masked by immutable scrollback. Under the
[[destructive resize reset]] it is central: a reset repaints from source at a new width, so a boundary
carried in rendered coordinates is wrong exactly when it is used.

Because a reset repaints what it erased, freeze boundaries are also **per [[geometry epoch]]**. A new
epoch begins with the boundary re-derived at the new geometry rather than inherited from the old one.

## Acceptance criteria

- [ ] The commitment boundary is stored in source coordinates
- [ ] The boundary is mapped into the current width's projection when rendering
- [ ] Resizing mid-answer neither truncates the live tail nor repeats already-committed content,
      asserted against the offline screen model
- [ ] Narrowing mid-answer is asserted specifically, since it increases the styled-line count
- [ ] Widening mid-answer is asserted specifically, since it decreases the styled-line count
- [ ] Resizing mid-answer with a markdown table in flight is asserted, since table column allocation
      is width-dependent
- [ ] A new geometry epoch re-derives the boundary rather than inheriting it
- [ ] The completed answer's content after several resizes mid-stream equals the content produced with
      no resize at all

## Blocked by

- `.scratch/destructive-resize-epoch/issues/01-destructive-reset-replaces-erase-regime.md`
