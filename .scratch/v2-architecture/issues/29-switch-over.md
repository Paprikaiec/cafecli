# 29: Switch-over — real-terminal smoke run, then delete v1

**Parent:** `.scratch/v2-architecture/PRD.md` ("Switch-over"; criterion 3) · ADR 0020 D9
**Blocked by:** 25, 26, 27, 28
**Status:** ready-for-human

**What to build:** v2 becomes Cafecli. First the maintainer runs a smoke check in a real terminal with real
providers; after their go, one commit makes v2 the default and deletes v1.

**Smoke run** (record results under `## Comments`; this is a smoke run, not a render acceptance — the open ADR 0016 /
0017 real-terminal items stay where they are):

- [ ] A question that needs several tools; approve one call, deny another.
- [ ] ESC mid-run cancels and keeps typed input; Ctrl+C on an empty box twice exits; Ctrl+D mid-run does nothing.
- [ ] Resize the window while an answer streams.
- [ ] Ctrl+O overlay during a run follows the tail; an approval closes it.
- [ ] Background task → notification → autonomous run.
- [ ] `/model` switch mid-session.
- [ ] `/save`, restart, and the fact is recalled.
- [ ] An MCP tool call; a skill load; a snapshot revert.
- [ ] A long session that compacts and continues.
- [ ] `run --file` with `--trace-file`, read by the eval normalizer.

**Switch-over commit** (after the go):

- [ ] v2 is the default; the `-Dcafecli.v2` flag is gone; the jar starts v2 with no flag.
- [ ] The v1 spine, its v1-only tests, and the parity harness are deleted.
- [ ] `serve --http`, its thread store, and its runtime task runner are removed; the CLI parser, its tests, and README
      are updated.
- [ ] AGENTS.md updated: the single execution path; the interception order (hooks → approval before execution,
      guards inside execution); core batch scheduling replaces the registry's batch executor; the "two compressions"
      invariant removed; the doc-sync rows for tools and models; the project snapshot.
- [ ] `docs/agents-reference.md` updated where it describes v1 classes; ADR 0020 marked implemented.
- [ ] The architecture tests drop the "no v1 import" rule; `mvn clean package`, `mvn test -Pquick`, the contract set,
      and the screen-truth tests named in AGENTS.md are green.
- [ ] Moving leaves into capability packages is left as a follow-up, not part of this commit.

**2026-10-21 (from ticket 07 review).** `tool.FileTools` now has an explicit `BooleanSupplier` cancellation constructor used by v2. Its old one-argument constructor still delegates to `runtime.CancellationContext::isCancelled` solely to preserve v1 behavior during migration. Remove that bridge/import when deleting the v1 runtime; keep the leaf tests, passing explicit cancellation where appropriate. The public `WorkspaceTools` factory uses invocation-scoped observers/token suppliers and never starts the v1 global cancellation context. Also remove obsolete javadoc references to deleted spine classes in leaf files, as already noted on tickets 01–03.
