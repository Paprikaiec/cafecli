# 16: Images

**Parent:** `.scratch/v2-architecture/PRD.md` (`ai` image blocks; projection stages) · ADR 0020 D3, D4
**Blocked by:** 02, 09
**Status:** ready-for-agent

**What to build:** Images work in v2 as in v1: an image referenced in the user's input reaches the model, images
returned by tools reach the model, and images from earlier runs are replaced by a placeholder so they don't keep
filling the context.

- Image references in user input become image blocks on the user entry (through the leaf-neutral parts from ticket
  02).
- Image tool results become image blocks, matching v1's placement.
- A projection stage replaces images in older entries with today's placeholder text, keeping the latest intact.

- [ ] Seam 1: a user message with an image reference yields an image block in the recorded request.
- [ ] Seam 1: an image attached two runs ago shows the placeholder; the latest one is intact; the log still holds
      both images.
- [ ] Seam 1: an image tool result reaches the next request as an image block.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 02 review).** `ImageReferenceParser.parse()` → `ParsedInput(text, images)` is the
leaf-neutral entry point; v1 wraps it in `llm/UserMessages.fromInput`. `ImageReferenceParserTest` only exercises the v1
wrapper — re-express its cases against `parse()` (or at seam 1) here, or the parser loses all coverage at switch-over.

**2026-10-21 (from ticket 12 review).** Ticket 12 has two projection phases: source-range normalization first, then ordinary view projectionStage in declaration order. Historical image stripping belongs to ordinary view projection so it cannot shift Summary source ranges. ContextTokens currently traverses neutral messages using the existing MemoryEntry text estimator and v1 image estimate; preserve actual request/token semantics when adding image history handling.

**2026-10-22 (from ticket 14 review).** MessageKind.userPrefix lets run-start SkillBody entries merge into the actual preceding UserEntry only during pure conversion. Adjacent text merges to v1's exact bytes while other UserContent blocks survive. Preserve this when parsing user images and add combined skill-prefix/image coverage; memory recall / steering are separately presented user messages and must not become prefix targets. Source user entries and skill metadata remain unchanged.
