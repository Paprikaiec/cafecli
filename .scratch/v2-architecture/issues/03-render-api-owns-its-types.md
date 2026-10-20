# 03: The renderer's tool-call API takes render-owned types

**Parent:** `.scratch/v2-architecture/PRD.md` (Migration — v1 freeze exception; `ui` layer) · ADR 0020 D9 · ADR 0017
**Blocked by:** 01
**Status:** ready-for-agent

**What to build:** The renderer's typed tool events (tool calls, tool results) take render-owned view types instead of
the LLM client's tool call and the registry's execution result, so v2's event → render adapter can drive render
without v1 types. v1's agent and the renderer-backed approval handler convert at their call sites. What appears on
screen is unchanged.

- [ ] The `render` package imports neither `LlmClient` nor `ToolRegistry`.
- [ ] The screen-truth tests named in AGENTS.md (`ScreenTruthRegressionTest`, `InputBoxLiveRegionTest`,
      `ToolCallBlockTest`, `CaptureDisplayInterceptionGuardTest`, `HardTerminationCouplingTest`) and
      `-Pphase16-smoke` pass with unchanged assertions.
- [ ] `mvn test -Pquick` is green.
