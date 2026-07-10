# 09 — 语法高亮升级：分色 + 跨行状态 + 数据/脚本语言支持

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-pipeline/PRD.md`（ADR 0011：升级正则高亮，否决 tm4e/tree-sitter）

## What to build

升级现有正则 `CodeHighlighter` 并接入 CodeBlock 渲染：

- 迁出 `tui/` 到渲染中立包；输出从 ANSI 字符串改为**样式 span**（AttributedString）。
- 分色：关键字 / 字符串 / 注释 / 数字 / 注解各自独立颜色（现状关键字与数字共用一色、字符串不上色）。
- 尾随注释（代码后 `// …` / `# …`）正确识别；**跨行状态**：块注释、Python 三引号字符串以行间携带状态处理（块渲染按序迭代行，小状态对象即可）。
- 新增语言：**JSON / YAML / shell / SQL / XML**（agent 输出高频格式）；既有 Java/Kotlin/Python/JS/TS/Go/Rust 保留。
- 接入 CodeBlock 行缓存：完成行高亮一次进缓存，热路径零逐帧开销（缓存命中计数验证）。
- Lanterna 侧旧引用同步更新，行为不回归。

可演示：agent 输出 Java + JSON + shell 代码块，各 token 类型分色清晰。

## Acceptance criteria

- [ ] 五类 token 分色（每语言至少关键字/字符串/注释断言）
- [ ] 跨行块注释 / 三引号字符串高亮正确（跨行状态测试）
- [ ] JSON / YAML / shell / SQL / XML 高亮生效
- [ ] 高亮仅在完成行执行一次（缓存命中计数断言，风暴下零重复高亮）
- [ ] 未知语言优雅降级（不再默认按 Java 关键字乱染，改为仅字符串/注释/数字通用高亮或纯文本）
- [ ] Lanterna 路径编译与行为不回归

## Blocked by

- `03-live-region-strip-compositor.md`
