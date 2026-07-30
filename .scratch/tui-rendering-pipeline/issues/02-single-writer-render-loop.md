# 02 — 单写者渲染循环：33ms 合帧 + 帧泵注入 + RenderStats + BSU/ESU

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-pipeline/PRD.md`（ADR 0011「帧调度」）

## What to build

建立单渲染线程：**全部终端写出集中到一个线程**，producer（回合执行线程、任务虚拟线程、spinner tick、status 更新）只投事件进 MPSC 队列（`LinkedBlockingQueue` + dirty flag 足够，不引 JCTools）。

- ~33ms leading+trailing 合帧：一帧内的 N 个 delta 合并为一次重绘；每脏帧单次 write+flush。
- **帧触发抽象可注入**：测试手动泵帧，确定性断言合帧行为，无 sleep、无 flaky。
- `RenderStats` 常开计数器：帧 / 写 / 字节 / flush / 合并事件 / 缓存命中；`-Dcafecli.render.stats=true` 回合结束输出一行统计（对齐既有 token 统计行模式）。
- BSU/ESU（`ESC[?2026h/l`）能力探测 + 每帧包裹。
- **首个租户：thinking 活动区迁入渲染循环**——`InlineActivityDisplay` 的独立 ticker 线程与逐 delta 全清重建死亡；spinner 以 250ms 独立节奏作为事件源投队列；视觉行为等价（spinner + 灰色竖线 reasoning 预览 + esc 提示 + 固定高度）。

可演示：长思考流式平滑无闪烁；stats 输出可见帧合并效果。

## Acceptance criteria

- [ ] 风暴测试（帧泵驱动）：flush 次数 ≤ 脏帧数 + 常数（每脏帧恰一次写出）
- [ ] 逐 delta 工作量 O(delta)：reasoning 缓冲不整体重切分（计数器验证）
- [ ] producer 线程不直写终端（唯一写者 = 渲染线程）
- [ ] `-Dcafecli.render.stats=true` 输出统计行；默认关闭时零输出
- [ ] 终端支持时每帧被 BSU/ESU 包裹（字节断言）；不支持时无裸序列
- [ ] thinking 区视觉行为等价（phase16-smoke 对应测试替换）

## Blocked by

- `01-block-event-seam.md`
