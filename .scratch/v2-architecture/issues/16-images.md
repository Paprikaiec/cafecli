# 16: Images

**Parent:** `.scratch/v2-architecture/PRD.md` (`ai` image blocks; projection stages) · ADR 0020 D3, D4
**Blocked by:** 02, 09
**Status:** done

**What to build:** Images work in v2 as in v1: an image referenced in the user's input reaches the model, images
returned by tools reach the model, and images from earlier runs are replaced by a placeholder so they don't keep
filling the context.

- Image references in user input become image blocks on the user entry (through the leaf-neutral parts from ticket
  02).
- Image tool results become image blocks, matching v1's placement.
- A projection stage replaces images in older entries with today's placeholder text, keeping the latest intact.

- [x] Seam 1: a user message with an image reference yields an image block in the recorded request.
- [x] Seam 1: an image attached two runs ago shows the placeholder; the latest one is intact; the log still holds
      both images.
- [x] Seam 1: an image tool result reaches the next request as an image block.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 02 review).** `ImageReferenceParser.parse()` → `ParsedInput(text, images)` is the
leaf-neutral entry point; v1 wraps it in `llm/UserMessages.fromInput`. `ImageReferenceParserTest` only exercises the v1
wrapper — re-express its cases against `parse()` (or at seam 1) here, or the parser loses all coverage at switch-over.

**2026-10-21 (from ticket 12 review).** Ticket 12 has two projection phases: source-range normalization first, then ordinary view projectionStage in declaration order. Historical image stripping belongs to ordinary view projection so it cannot shift Summary source ranges. ContextTokens currently traverses neutral messages using the existing MemoryEntry text estimator and v1 image estimate; preserve actual request/token semantics when adding image history handling.

**2026-10-22 (from ticket 14 review).** MessageKind.userPrefix lets run-start SkillBody entries merge into the actual preceding UserEntry only during pure conversion. Adjacent text merges to v1's exact bytes while other UserContent blocks survive. Preserve this when parsing user images and add combined skill-prefix/image coverage; memory recall / steering are separately presented user messages and must not become prefix targets. Source user entries and skill metadata remain unchanged.

**2026-10-22 (approved preparation refinement; acceptance pending).** Source verification found v1 prepends drained
skill bodies before parsing image input. Parsing the pieces separately changes image-only fallback text, trimming,
references inside skills and disclaimer placement. A generic pre-append RunInput preparation seam is approved:
ordered harness stages contribute raw SkillBody entries, then Images parses their combined prospective user text
once. Source UserEntry retains actual user text plus user-origin image blocks; its optional cached presentation
contains the exact combined parse and all attachments. Successful reference offsets from the same parser loop
distinguish prefix-origin images without another parse or algorithm copy; old parse() behavior stays intact.
Prepared prefix count prevents duplicate merging while later run-start prefixes remain eligible. Existing memory,
Hooks and freshness writers remain after the user append and receive raw input. Default constructors remain
compatible; new metadata must round-trip, stay agent-scoped and clear with the source log. Ordinary historical-image
projection transforms source and cached presentation in its returned view only; Summary source normalization remains
first. Tool images are presented after the whole contiguous tool-result batch, one user message per image-bearing
result in source order, with the exact v1 intro; source tool results retain their image blocks. No new parity
exception or image I/O in pure projection is approved. ADR0020/PRD record the tradeoffs.

**2026-10-22 (leader acceptance).** Images attaches after Skills in actual Composition. User references load once
into neutral image blocks regardless of declared model image capability; invalid-reference text and current parser
grammar are retained. The existing parser's same single pass now exposes successful UTF-16 reference offsets;
its old parse() entry point and assertions remain unchanged. Twelve neutral parser cases retain coverage after v1
deletion. Combined preparation preserves exact v1 skill-before-image text for image-only input and references inside
skills, partitions user/prefix attachment origins, keeps raw SkillBody separate, preserves existing nontext input
blocks and caches only changed image presentation. Core remains product-neutral; its default converter also honors
prepared presentation. Old UserEntry construction/JSON stays compatible, later prefix entries remain eligible,
prefix counts reset per actual user, and all metadata round-trips.

Historical stripping operates on an immutable ordinary view after Summary source normalization, including cached
presentation. A new real user run removes every prior-run image, even when its own input is plain text; current
images remain. Source users, cached bytes and source tool payloads remain unchanged. Tool images follow the whole
contiguous result batch as source-ordered separate user messages with exact v1 text. Session/profile isolation,
clear/cancel, original run-start input, Summary/memory/steering composition and no repeated file reads are covered.
Observed input/tool/history/prefix/origin failures all turned green. Moving image view into source normalization
made the composed Summary regression fail (expected4/actual5); restored production passed. Independent fresh
Standards and Spec reviews of the full tracked/untracked diff were clean.

Implementer focused: **53/0/0/0**, broad44 suites: **426/0/0/0**. Independent leader quick: **1768/0/0/0**;
independent named92 suites: **794/0/0/0**, including architecture8, image leaves, ai/wire, core/harness/app,
Web/RAG/browser/tool/MCP and registry/HITL shims. Clean package passed. `/tmp/cafecli16-jar-check.py` compiled against
the jar and checked recorded requests, exact tool-image placement/placeholders, source immutability after deleting
the input file, actual Composition skill/image-only parsing parity, prefix-origin separation, round-trip/clear and
actual CLI exact output. Packaged08/10/11/12/13/14/15 regression fixtures all passed too. Logs:
`/tmp/cafecli16-broad.log`, `/tmp/cafecli16-stable-focused.log`, `/tmp/cafecli-ticket16-leader-{quick,named,package}.log`.
No real keys/external services, render changes, real-terminal claim or push; unrelated scratch files remain untouched.
