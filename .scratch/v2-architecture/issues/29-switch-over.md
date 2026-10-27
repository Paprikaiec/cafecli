# 29: Switch-over — real-terminal smoke run, then delete v1

**Parent:** `.scratch/v2-architecture/PRD.md` ("Switch-over"; criterion 3) · ADR 0020 D9
**Blocked by:** 25, 26, 27, 28
**Status:** done

**What to build:** v2 becomes Cafecli. First the maintainer runs a smoke check in a real terminal with real
providers; after their go, one commit makes v2 the default and deletes v1.

**Smoke run** (record results under `## Comments`; this is a smoke run, not a render acceptance — the open ADR 0016 /
0017 real-terminal items stay where they are):

- [x] A question that needs several tools; approve one call, deny another.
- [x] ESC mid-run cancels and keeps typed input; Ctrl+C on an empty box twice exits; Ctrl+D mid-run does nothing.
- [x] Resize the window while an answer streams.
- [x] Ctrl+O overlay during a run follows the tail; an approval closes it.
- [x] Background task → notification → autonomous run.
- [x] `/model` switch mid-session.
- [x] `/save`, restart, and the fact is recalled.
- [x] An MCP tool call; a skill load; a snapshot revert.
- [x] A long session that compacts and continues.
- [x] `run --file` with `--trace-file`, read by the eval normalizer.

**Switch-over commit** (after the go):

- [x] v2 is the default; the `-Dcafecli.v2` flag is gone; the jar starts v2 with no flag.
- [x] The v1 spine, its v1-only tests, and the parity harness are deleted.
- [x] `serve --http`, its thread store, and its runtime task runner are removed; the CLI parser, its tests, and README
      are updated.
- [x] AGENTS.md updated: the single execution path; the interception order (hooks → approval before execution,
      guards inside execution); core batch scheduling replaces the registry's batch executor; the "two compressions"
      invariant removed; the doc-sync rows for tools and models; the project snapshot.
- [x] `docs/agents-reference.md` updated where it describes v1 classes; ADR 0020 marked implemented.
- [x] The architecture tests drop the "no v1 import" rule; `mvn clean package`, `mvn test -Pquick`, the contract set,
      and the screen-truth tests named in AGENTS.md are green.
- [x] Moving leaves into capability packages is left as a follow-up, not part of this commit.

**2026-10-21 (from ticket 07 review).** `tool.FileTools` now has an explicit `BooleanSupplier` cancellation constructor used by v2. Its old one-argument constructor still delegates to `runtime.CancellationContext::isCancelled` solely to preserve v1 behavior during migration. Remove that bridge/import when deleting the v1 runtime; keep the leaf tests, passing explicit cancellation where appropriate. The public `WorkspaceTools` factory uses invocation-scoped observers/token suppliers and never starts the v1 global cancellation context. Also remove obsolete javadoc references to deleted spine classes in leaf files, as already noted on tickets 01–03.

## Comments

**2026-10-27 (assisted checks; human gate still pending).** The maintainer requested a working v2 test launch and
help executing the long manual plan. The accepted28 JAR was exercised through its existing flag, with isolated
storage and credentials privately loaded from the maintainer-identified `.env`. Fresh contracts727 and parity34
passed with zero failures/errors/skips. Real DeepSeek checks covered packaged headless file/skill/child/status
behavior, compatible traces through the actual eval normalizer, saved-memory recall after a fresh JVM, model
switch/history/tool continuity, snapshot restore bytes, accelerated-window compaction/continuation and background
idle autonomous completion at the session seam. A local HTTP MCP echo completed with the exact result; the model's
requested bare-answer formatting was not met and is retained separately. Initial temporary compaction-probe setup
failure and corrected retest are both preserved. Details and evidence boundaries are in
`../assisted-test-results.md`; evidence is under
`/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-assisted-_iif334y/evidence/`.
`../check-v2-terminal.sh` prepares three short human observation stages. None of the human checkboxes above is
checked by this automated run; native-screen acceptance, the historical notice observation and the explicit
switch-over decision retain their existing status. No product source/default-path change or switch-over commit
was made.

**2026-10-27 (maintainer Ghostty results read; resize clarification pending).**
`human-terminal-results.tsv` contains two attempts. The first records PASS for approvals with correct approved/denied
bytes, ESC/draft and idle Ctrl+C, overlay following/approval wake, and overlay Ctrl+C with a visible notice and
preserved draft. These are human observations; the approval and overlay smoke checkboxes are checked accordingly.
The second records SKIP for previously tested groups and a raw approved-file byte FAIL after the fixture reset;
that raw result is retained, with its unexercised action precondition distinguished from a product regression.
Both attempts label busy Ctrl+D FAIL solely because it had no effect. That is the intended contract if streaming
continued and the app stayed open; idle empty-box Ctrl+D is the exit case. Resize notes say no reaction / no resize
seen, without a screen capture or an explicit observation about dragging the terminal window edge. Clarification
was requested before declaring a resize defect or pass. The wizard wording now makes both pass conditions explicit.
No product code was changed, no historical race was declared fixed, and no switch-over go was inferred.

**2026-10-27 (explicit maintainer acceptance and go).** After the recorded-result review and clarification that
busy Ctrl+D is an intentional no-op, the maintainer stated “OK，那现在所有测试都通过了，帮我正式切换到 V2”.
This closes this ticket's human acceptance gate and explicitly authorizes the one switch-over commit, including
retiring v1, its parity harness and the HTTP runtime. Smoke checkboxes reflect the maintainer's acceptance of the
combined live-provider, automated and Ghostty observations; earlier raw FAIL/SKIP notes remain preserved. This does
not invent additional screen captures or close ADR0016/0017's independent acceptance or the historical modal race.
The root owns all Maven/probe execution. The base is accepted28 cb6628b7; unrelated
`.scratch/sessions-context-memory/` is preserved.

**2026-10-27 (switch-over implementation and final validation).** Main now unconditionally delegates to the
application entry. The migration flag, v1 Agent/LLM/HITL/runtime spine, legacy CLI wrappers, short-term store and
compressors, registry/batch executor, HTTP thread/task runtime and parity package/resources are retired. Reusable
input, image, MCP, memory, skill, search and task strategy tests are bound to surviving APIs. Neutral trace field
contracts, prepared-image trace behavior and HeadlessRunPresenter/session/CLI/fake-hold regressions remain. FileTools
requires explicit cancellation; ShellHook no longer reaches the retired thread-local cancellation context. SQLite
is retained for RAG. Production leaves stay in their existing packages.

Final validation on Java21, with `env -u NO_COLOR TERM=xterm`: `mvn clean package` passed; quick1780/209 classes,
contracts729/73 classes and named screen-truth87/5 classes all passed with zero failures/errors/skips. Earlier
compilation/quick failures (old test bindings, an erroneously removed RAG dependency, search fixture and documentation
wording) remain as superseded evidence. Reviews found and repaired the neutral status/task-test preservation gaps.
After tests, one stale Javadoc line was corrected; the final package rebuild produced byte-identical content for all
680 application class files, so the passing behavioral evidence remains applicable. Standards and Spec review
findings were resolved; final metadata review follows the same source delta.

Final JAR SHA-256: `35c95c7e4530489ed4ba9e888b64fd065151a6683bd50039443420159f7d7d6c`. Actual packaged Main was exercised
without a migration flag: guarded read/write bytes, retained reasoning, compatible JSONL and the real eval normalizer
passed; `serve --http` exited2; the JAR contains no legacy spine classes. The same default-entry fixture failed
against accepted28's v1 default (exit1), demonstrating that the check distinguishes the switch. A fresh real
DeepSeek (`deepseek-flash`) packaged invocation without the flag also passed exact file bytes and normalized trace
checks. Evidence: `/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-ticket29-9l4n3d87/final2/`,
`final-package.json`, `packaged-76f0149b/` and `live-default-z93varri/` under that root. No push, main-checkout change,
leaf package move or ADR0016/0017 full-render acceptance is part of this switch-over.
