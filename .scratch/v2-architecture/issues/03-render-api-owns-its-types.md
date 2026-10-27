# 03: The renderer's tool-call API takes render-owned types

**Parent:** `.scratch/v2-architecture/PRD.md` (Migration — v1 freeze exception; `ui` layer) · ADR 0020 D9 · ADR 0017
**Blocked by:** 01
**Status:** done (2026-10-20)

**What to build:** The renderer's typed tool events (tool calls, tool results) take render-owned view types instead of
the LLM client's tool call and the registry's execution result, so v2's event → render adapter can drive render
without v1 types. v1's agent and the renderer-backed approval handler convert at their call sites. What appears on
screen is unchanged.

- [x] The `render` package imports neither `LlmClient` nor `ToolRegistry`.
- [x] The screen-truth tests named in AGENTS.md (`ScreenTruthRegressionTest`, `InputBoxLiveRegionTest`,
      `ToolCallBlockTest`, `CaptureDisplayInterceptionGuardTest`, `HardTerminationCouplingTest`) and
      `-Pphase16-smoke` pass with unchanged assertions.
- [x] `mvn test -Pquick` is green.

## Comments

**2026-10-20 — done (leader review).**

- Render-owned views in `com.cafecli.render`: `ToolCallView(id, name, argumentsJson)` and
  `ToolResultView(id, name, argumentsJson, text)` — exactly the fields render read; no status, blocks or timing (render
  never read them; if it ever needs an outcome, add a typed status field — don't sniff the text, AGENTS.md 工具).
- v1 converts in `agent/ToolRenderViews` (package-private, deleted with v1). `text` is `ToolExecutionResult.result()`,
  the string the model is sent; it is now computed on the agent thread at hand-over instead of on the render thread
  (inputs are immutable; A/B identical).
- The ticket's "the renderer-backed approval handler converts at its call sites" had nothing to do: `RendererHitlHandler`
  never calls the tool-event API. Render still imports `hitl.ApprovalRequest` / `ApprovalResult` / `ApprovalPolicy`
  for `promptApproval` — out of this ticket's acceptance; ticket 25 owns it.
- Verified by the leader: `-Pquick` 1352/0/0/0; `-Pphase16-smoke` 456/0/0/0; the five screen-truth tests plus
  render / HITL / registry suites 236/0/0/0; string literals unchanged; test diffs are input construction only. The
  implementer A/B'd HEAD vs new on 2532 lines of renderer output (widths 12–120, ANSI on) — identical.
- Added in review: `ToolRenderViewsTest` pins the field mapping (every view field is a String, so a swap would pass
  every renderer test); proven red by swapping `name` / `argumentsJson`.
