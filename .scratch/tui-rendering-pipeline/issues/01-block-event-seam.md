# 01 — 块事件 seam：StreamRenderer 产出类型化块事件，嗅探协议退役

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-pipeline/PRD.md`（ADR 0010 + 0011）

## What to build

Tracer bullet：打通「producer → 块事件 → 渲染器 → 终端字节」全链路，**视觉行为不变**。

- `Renderer` 接口新增类型化块事件（user prompt / answer prose / code block(lang) / reasoning；工具调用组沿用既有 `appendToolCalls` 并入块事件家族），全部给 **default 实现落回 `stream()` 线性文本**——`PlainRenderer` / `LanternaRenderer` / spawner no-op renderer 零改动兼容。
- `Agent.StreamRenderer` 在**源头**分段：markdown 围栏检测在 markdown 源上做（容忍未闭合围栏），reasoning/content delta 路由为块事件。不再「渲染成 `┌─` 装饰行 → 下游从 ANSI 流正则嗅探回来」。
- `InlineRenderer` 实现块事件：视觉输出与现状等价（代码块折叠头、工具调用组照旧），但内部删除 `┌─ code` 字符级嗅探状态机；块事件传 String，byte-chunk 解码路径消亡 → **UTF-8 分块边界乱码 bug 就地修复**。

可演示：改造前后 inline / plain 输出肉眼等价；嗅探协议死亡由测试锚定。

## Acceptance criteria

- [ ] `Renderer` 块事件 + default 落回 `stream()`；`PlainRenderer` 与 headless `run --file` 输出与改造前一致（golden 断言，零回归）
- [ ] `StreamRenderer` 喂 delta 风暴 → 断言块事件序列正确（prose / code(lang) / reasoning 边界，含未闭合围栏容忍）
- [ ] `InlineRenderer` 不再嗅探 `┌─ code`（协议删除），代码块折叠头显示与现状等价
- [ ] CJK 文本跨 write 分块渲染不乱码（回归测试锚定）
- [ ] 测试 seam：`Agent.StreamRenderer` ← `LlmClient.StreamListener` 喂 delta；`InlineRenderer` 注入 PrintStream 捕获字节

## Blocked by

None - can start immediately
