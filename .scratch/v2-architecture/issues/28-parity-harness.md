# 28: v1 / v2 parity harness

**Parent:** `.scratch/v2-architecture/PRD.md` (seam 4; "Intentional behavior changes"; switch-over criterion 2)
**Blocked by:** 13, 14, 15, 16, 18, 19, 20, 21, 22
**Status:** ready-for-agent

**What to build:** Evidence that v2 behaves like v1 except where the PRD says it shouldn't: the same scenarios run
through v1 headless and v2 headless, and every difference is explained by a numbered intentional change. Temporary —
deleted with v1.

- Scenario scripts readable by both a v1 stub LLM client and the v2 fake provider. Cover at least: plain answer;
  read → edit → run tests; approval denied; PreToolUse BLOCK; Finish Gate block-then-pass; compaction; background task
  with notification; cancel mid-run; MCP tool call; skill load.
- Compare final answer, files on disk, and trace records (normalized for ids and timing).
- Each difference must match an entry in "Intentional behavior changes"; anything else fails. Adding an entry needs
  the maintainer's sign-off and an update to the PRD and ADR 0020.
- Feed both traces through the eval normalizer; summaries must match unless an intentional change explains it.

- [ ] Every scenario passes, with each remaining difference labeled by its intentional-change number.
- [ ] Normalizer summaries match for every scenario not covered by an intentional change.
- [ ] `mvn test -Pquick` is green.
