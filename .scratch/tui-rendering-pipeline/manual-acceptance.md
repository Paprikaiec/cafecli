# TUI rendering pipeline manual acceptance

Date prepared: 2026-07-12
Worktree: `/Users/wyw/Project/paicli-tui-rendering`
Issue: `issues/12-final-sweep-manual-acceptance.md`

## Automated preflight

- [x] `mvn clean test -Pphase16-smoke </dev/null` — 265 tests, 0 failures, 0 errors
- [x] `mvn clean test -Pquick </dev/null` — 861 tests; only the two documented
  `CodeIndexTest` baseline failures
- [x] `mvn clean test -DskipTests=false </dev/null` — 958 tests; only the same two
  `CodeIndexTest` baseline failures; `TerminalMarkdownRendererTest` is green
- [x] `git diff --check`
- [x] No source, test, or POM references remain for `FoldableBlock`, `BlockRegistry`,
  `InlineActivityDisplay`, `ToolCallRenderer`, or `InlineDiffRenderer`
- [x] Independent #10/#11 review findings were reproduced and regression-tested: URL false
  positives, OSC 8 row balance, prompt/approval coalescing, and buffered-input first-frame ordering
- [x] Follow-up issue registered: `issues/13-approval-diff-preview.md`

Known baseline failures:

- `CodeIndexTest.reportsProgressThroughListener`
- `CodeIndexTest.testIndexCurrentProject`

Known unrelated baseline boundary: interactive Plan-mode HITL can park on `ApprovalPump` because
the Plan wait loop does not drain that pump. This predates the rendering changes and is not exercised
by the issue #11 ReAct/background approval path.

## Host readiness checked on 2026-07-12

- macOS 26.4 (build 25E241)
- Terminal.app is installed
- iTerm2 is not installed on this host
- No supported provider API key is present in the environment
- The isolated worktree has no `.env`
- The packaged JAR therefore stops at the expected missing-key bootstrap guard before an interactive
  renderer session can begin

No credentials were read or printed. The visual checklist must be run on a host with iTerm2,
Terminal.app, and one configured provider key; installing software or copying secrets into this
isolated worktree was deliberately not inferred from the implementation request.

## Human sign-off

Build and start from this worktree:

```bash
mvn clean package
java -jar target/paicli-1.0-SNAPSHOT.jar
```

Record terminal/app versions and mark each item only after direct observation.

### iTerm2

- [ ] Long streamed reasoning is smooth at roughly 30 fps with no transcript flicker
- [ ] `Ctrl+O` toggles multiple reasoning/tool/code/diff blocks without residue or duplicates
- [ ] `Ctrl+T` enters/exits the overlay without scrollback loss; block navigation and folding work
- [ ] A rendered `path:line` link opens the expected file/line with Cmd-click
- [ ] Diff add/delete backgrounds span the row; narrow resize reflows correctly
- [ ] Foreground and background-task approvals show caller context and preserve
  approve/reject/skip/modify/approve-all behavior
- [ ] ESC cancellation still cancels the foreground turn and attached tasks only

Terminal/app version: ____________________
Result/notes: _____________________________
Signed by/date: ___________________________

### Terminal.app

- [ ] Long streamed reasoning is smooth at roughly 30 fps with no transcript flicker
- [ ] `Ctrl+O` toggles multiple reasoning/tool/code/diff blocks without residue or duplicates
- [ ] `Ctrl+T` enters/exits the overlay without scrollback loss; block navigation and folding work
- [ ] Unsupported OSC 8 capability falls back to color with no visible raw escape sequence
- [ ] Diff colors/fallbacks and narrow resize reflow correctly
- [ ] Foreground and background-task approvals show caller context and preserve behavior
- [ ] ESC cancellation still cancels the foreground turn and attached tasks only

Terminal/app version: ____________________
Result/notes: _____________________________
Signed by/date: ___________________________
