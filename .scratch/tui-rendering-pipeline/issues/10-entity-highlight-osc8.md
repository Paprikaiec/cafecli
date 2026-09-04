# 10 — 实体高亮 + OSC 8：正文路径/函数引用凸显与可点击跳转

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-pipeline/PRD.md`（实体高亮 + OSC 8 决策）

## What to build

AnswerSegment / Reasoning 块的 prose 行后处理（`AttributedStringBuilder.styleMatches`，保留既有样式叠加）：

- **文件路径**：含 `/` 或已知代码扩展名的 token（`Foo.java`、`pom.xml`、`src/.../Agent.java:232`），可带 `:line` 后缀 → 统一颜色（如青色）。
- **函数/方法引用**：要求尾随括号（`executeTools()`、`Agent.run()`、`Class#method()`）→ 另一颜色（如琥珀色）。
- 反引号内外均生效；反引号 inline-code 底样式保留。
- 模式保守、误报趋零：版本号（`1.0.2`）、普通括号语句、`e.g.`、URL 不误命中（负例断言集）。
- **OSC 8**：`path:line` 匹配包 `file://` 超链接（iTerm2 等 Cmd+点击直达），经 `TerminalCapabilities` 门控；不支持的终端纯颜色、零裸序列。

可演示：agent 回答里提到的 `ToolRegistry.java:308` 青色可点击、`executeTools()` 琥珀色。

## Acceptance criteria

- [ ] 路径模式命中（相对/绝对/带 `:line`/裸文件名+已知扩展名），统一颜色
- [ ] 函数引用命中（要求尾随括号，支持限定符与 `#`），另一颜色
- [ ] 负例集零误报（版本号 / 普通括号 / `e.g.` / URL / 纯数字）
- [ ] 既有样式不被破坏（styleMatches 叠加断言：粗体/引用色保留）
- [ ] OSC 8：支持时 `path:line` 包 `file://` 链接（字节断言）；不支持时纯颜色无裸序列
- [ ] 高亮在完成行执行一次进缓存（零逐帧开销）

## Blocked by

- `03-live-region-strip-compositor.md`
