# 03 — 富 digest：schema 签名 + 取样 + 内联脱敏 + omitted counts

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/mcp-result-referencing/PRD.md`（ADR 0009）

## What to build

把 02 的“最小引用”升级为真正有用的确定性 digest，落在新的纯组件 `StructuredContentRenderer`（无 fs、无模型调用）。digest 形态（grill 决策，精确刻画）：

- **schema 签名**：紧凑 type signature，depth≤2、对象取前 10 key、数组取首元素类型，如 `[{requestId: string, url: string, status: number, timing: {...}}]`。
- **取样 digest**：数组显示总长度 + 前 3 元素；标量展开；嵌套超深折叠为 `{…}`；字符串截断 ≤120 chars；必带 **omitted counts**（总数 / 省略数），让模型明确 digest 不完整。
- **内联脱敏**：敏感 key（`token`/`authorization`/`cookie`/`secret`/`password`/`apiKey`/`set-cookie` 等）的值在 digest 里 redacted；**落盘文件保持 raw、不脱敏**。
- 目标 ≤ ~2k chars；落盘/内联阈值判断基于 **compact** JSON 长度。
- `renderTextForLlm()` 渲染 `mcp_result_reference` 时用该 digest 取代 02 的最小文案（schema 签名 + 取样 + 路径）。

可演示（测试）：`StructuredContentRendererTest`（纯）断言 schema 签名、前 3 取样、深度/字符串截断、omitted counts、敏感 key 脱敏；对照断言落盘文件仍为 raw（含未脱敏敏感值）。

## Acceptance criteria

- [ ] schema 签名（depth≤2、对象前 10 key、数组元素类型）
- [ ] 取样：数组总长 + 前 3、标量展开、嵌套超深 `{…}`、字符串 ≤120、omitted counts
- [ ] 敏感 key 值**内联** redacted；落盘文件 raw（不脱敏）
- [ ] digest ≤ ~2k chars；阈值判断基于 compact JSON
- [ ] 测试（seam）：`StructuredContentRendererTest` 纯单元覆盖上述；端到端断言模型可见 digest 含样本且脱敏、文件 raw

## Blocked by

Issue 02（引用化 tracer bullet）
