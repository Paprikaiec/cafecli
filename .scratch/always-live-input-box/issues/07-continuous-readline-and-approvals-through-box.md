# 07 — 常驻 `readLine` + 审批经输入框应答；裸轮询 regime 退役

Status: ready-for-agent

## Parent

`.scratch/always-live-input-box/PRD.md`（ADR 0014）

## What to build

这是整个改造的**枢纽**：让行编辑器常驻读取，从此终端按键只有一个消费者，[[输入框]]在回合运行期间也活着。

现状是两套互斥 regime 按「有没有回合在跑」切换——空闲时行编辑器读，回合运行时主线程改用裸 raw-mode 字节轮询，而那个轮询器只认三个键、其余字节 drain 丢弃（这正是「输出时打的字被吞掉」的成因）。本 issue 让行编辑器**一直**在读，主线程退化为纯输入泵，兑现 ADR 0005 早已写下的承诺。

**审批必须同一 issue 内改造，无法拆分**：行编辑器一旦常驻，现有审批提示器的 raw-mode 单键读取会与它抢同一个底层读取器；而在本 issue 之前，回合期间根本没有输入框可供提交审批。二者循环依赖，只能一起落地。

审批改为经输入框应答（`y` / `n` / `a` + Enter），与普通提交同一通路：有审批待决时提交路由到审批解析，否则走事件循环提交。**空提交表示「再问一次」**——重新呈现审批卡而不给出决定，不得误判为拒绝。审批泵的 park/resolve 契约、请求方归属标注、会话级「全部批准」范围**保持不变**；审批卡仍由渲染线程绘制/更新/移除，**渲染线程上任何终端读取都是死锁 bug** 这条纪律不变。

右提示符随状态切换，让这条通路可被发现：空闲 `message / @path / @image` → 回合中 `steer · esc 停止` → 审批待决 `y / n / a`。

退役：回合期等待泵、回合期控制键轮询与其分类、转义序列分类与其类型枚举、输入突发读取、显示类控制处理。

**但退役的是 regime，不是骑在它上面的行为**（ADR 0016 D2）。两件事今天由裸轮询承载，删掉它不会自动幸存：

1. **回合中的 Ctrl+O 与 Ctrl+Y**。`Main.handleRuntimeDisplayControl`（经 `InteractiveRepl` 的等待泵调用）是这两个键在回合期间的**唯一**通路，而回合中开[[转写浏览层]]与回合中切 HITL 都是已交付、已在真机确认的行为。07 之后它们成为常驻读取器上的普通 widget，空闲与回合中两条入口**合并成一条**（这是简化，但不是自动发生的）。
2. **ESC 的延迟**。今天 `readRuntimeControl` 自己读到字节 27、取一次 80/20/120ms 突发再分类，取消在 **≤ ~120ms** 内触发。作为 JLine 键绑定，ESC 是所有方向键 / CSI 序列的**前缀**，键映射会等 `ambiguous-binding` 才触发短匹配，而默认是 **1000ms**（`LineReaderImpl.DEFAULT_AMBIGUOUS_BINDING`），本仓库从未设置过它。照 issue 原样删除会把「停下这次输出」从 120ms 变成一秒。
   **修法：把 `ambiguous-binding` 设为 ≈100ms，手写分类器照删。** JLine 的歧义超时**就是**那个分类器——同一个「等一等看后面还有没有」的算法，只是窗口不同；对齐我们已经在用的窗口，延迟与失败模式都停在今天的位置。
   **必须经构造器的 `variables` 传入**：`defaultKeyMaps()` 在 `LineReaderImpl` 构造器里被调用（`:347`），值在那时被读走，事后 `setVariable` 无效。这与 ADR 0014 记的 `INPUT_RC_FILE_NAME` 是同一条约束。

Ctrl+C 在本 issue 中**至少不能再杀进程**——行编辑器常驻即 JLine 的 SIGINT handler 常驻，中断以用户中断异常抛出。完整的取消键语义在 08 定义，本 issue 只需保证不退化。

**鼠标追踪的时序陷阱在本 issue 显形**（原属 11，因成因在这里）：行编辑器的清理路径每次都无条件发 49 字符的鼠标禁用序列，即便鼠标选项关闭，且无 override 钩子可拦。常驻读取器让每次提交都重入读取，该序列发射频次显著上升；而[[转写浏览层]]是唯一主动**开启**鼠标追踪的地方。必须保证浏览层的开启发生在读取让出之后、退出时自行恢复，否则滚轮被静默关掉。

## Acceptance criteria

- [x] 模型流式输出期间键入字符，字符出现在输入框中且可正常编辑（退格、光标移动）
- [x] 输出期间的补全、历史召回、实时高亮与空闲时一致
- [x] 输出期间按 Enter 提交不报错、不丢失（提交语义由 10 定义；本 issue 至少保证入列不丢）
- [x] 审批待决时 `y` / `n` / `a` + Enter 正确解析为批准 / 拒绝 / 全部批准
- [x] 审批待决时提交空内容 → 重新呈现审批卡，不产生决定
- [x] 审批泵的 park/resolve 契约、请求方归属、会话级全部批准范围不变；前台与后台审批都走同一通路
- [x] 渲染线程从不读取终端（审批卡只绘制）
- [x] 右提示符在 空闲 / 回合中 / 审批待决 三态下正确切换
- [x] 后台任务完成的 push 输出落在输入框上方，不打断正在编辑的内容
- [x] Ctrl+C 在回合运行期间不再终止进程
- [x] **回合中 Ctrl+O 仍能进入[[转写浏览层]]**，且与空闲时走同一个入口（`Main.openTranscriptOverlay(Renderer)`）；回合中 Ctrl+Y 仍能切 HITL 并立即刷新状态坞
- [x] **ESC 取消的触发延迟不高于今天**：`ambiguous-binding` 经构造器 `variables` 设为 ≈100ms（断言这个值确实生效，而不是断言 setter 被调用过）
- [x] 滚轮在[[转写浏览层]]内仍可用（鼠标追踪时序陷阱：浏览层的开启发生在读取让出之后，退出时自行恢复）
- [x] **捕获帧宽度与本帧宽度不符时输入框合成零行**（ADR 0016 D4）。**这条必须在本 issue 内落，不能推给 09**：今天它只在空闲时够得着，因为回合一开始 `doCleanup` 就把捕获帧清了；常驻读取器之后**回合期每一次 [[destructive resize reset]] 都够得着**。不只是观感——`inputLines.size()` 喂给活区尾预算，按旧宽度的行数算会让尾巴压过输入框与状态坞
- [x] 上一条的降级是有界且自愈的：完全不发 WINCH 的终端上，缩放后输入行暂时不画，用户下次按键即恢复（**不得**改成画上一宽度的行）
- [x] **可 grep 验收**：`Main.java` 与 `InteractiveRepl.java` 中 `terminal.reader()` 与 `enterRawMode()` 出现零次，并接入 CI。**模态自持终端不在此列**——`TranscriptOverlay` / `SlashPalette` 按[[模态租借]]合法拥有它们；判据是「输入 regime 里没有第二个按键消费者」，不是「全仓库没有 raw mode」
- [x] 回合期等待泵、控制键轮询与分类、转义序列分类与类型枚举、输入突发读取、显示类控制处理全部移除
- [x] 通过 06 建立的 seam 覆盖：输出期键入不被吞、审批应答路由、空提交重新询问、右提示符三态、回合中 Ctrl+O / Ctrl+Y
- [ ] `mvn test -Pquick` 与 `mvn test -Pphase16-smoke` 全绿
  > **不勾的理由是一条既有 flake，不是本 issue 的遗留**（2026-08-28 复核）：`-Pphase16-smoke` 401/401
  > 全绿；`-Pquick` 1171 条里 1 条失败，是 `TerminalMarkdownRendererTest.wrapsWideMultiColumnTableInsideTerminalWidth`
  > ——只在全量顺序下红，单独跑 13/13 全绿，且在 `-Pphase16-smoke` 里也绿（该 profile 同样跑这个类）。
  > 它先于本 issue 存在（issue 11 那轮已对 `git archive HEAD` 的干净副本复现过同样的失败）。
  > **本 issue 顺带修掉了 `-Pquick` 的挂起**：`InteractiveReplTest` 不再需要被排除（11 条 / 1.8s），
  > 成因正是本 issue 删掉的那条——回合期 `readRuntimeControl` 静默 drain 掉非 ESC 字节，于是测试在
  > 回合前写进管道的 `^D` 被吃掉、下一次 `readLine` 永远等不到 EOF。
- [x] 文档同步：AGENTS.md 中关于事件循环 / HITL 审批泵 / 输入 regime 的条目

## Blocked by

- `.scratch/always-live-input-box/issues/05-capture-display-input-line-into-live-region.md`
- `.scratch/always-live-input-box/issues/06-extract-interactive-repl.md`
