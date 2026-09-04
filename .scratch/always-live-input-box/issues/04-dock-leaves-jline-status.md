# 04 — 状态坞离开 JLine `Status`，改由渲染线程画

Status: ready-for-human
Type: HITL（需真实终端人工验收）

## Parent

`.scratch/always-live-input-box/PRD.md`（ADR 0014 决策 4）

## What to build

把底部状态坞从 JLine `Status` 托管改为由渲染线程作为[[活区]]行画出。

动因有二。其一，后续 `LineReader` 重绘会在**输入线程**调用 `Status.redraw()`，与渲染线程争终端；其二，`Status` 持有自己的 `Display` 直接写终端（Wave 0 实测每次更新约 37 字符），是 `LineReader` 截获管不到的第二个画者。

**取「根本不创建」而非「创建后挂起」**：状态坞不再向 JLine 索取 `Status` 实例。JLine 自身只调用非创建版查询，因此它恒为 null。Wave 0 实测证明挂起是不够的——挂起只让 `redraw()` 成 no-op，`size()` 仍返回原行数继续占用行预算；而不创建则同时消掉那个第二画者，并让后续 issue 05 里的一个重绘分支按构造不可达。

连带退役：活区按「终端行数 − 保留行数」计算重绘区高度的那条 seam（保留行数供给器消失，活区自己知道状态坞占几行）；DECSTBM 滚动区不再使用，活区靠既有的 clear→print→recreate 纪律自保；[[转写浏览层]]进入前挂起、退出后恢复状态坞的那对调用一并移除。

状态坞的**内容与布局不变**：上层模式 + MCP/Skill 摘要，下层 Auto Model / model / phase / ctx 百分比与 token / cost / elapsed / cwd。

## Acceptance criteria

- [x] 状态坞不再创建 JLine `Status`；查询非创建版返回 null
- [x] 状态坞内容、两层布局、各字段格式与改造前一致
- [x] 状态坞随模型 / phase / ctx / token / cost / elapsed 更新而刷新，且刷新只发生在渲染线程
- [x] 活区高度不再依赖外部保留行数供给器；活区不会覆盖状态坞，状态坞不会覆盖活区
- [x] 不再设置 DECSTBM 滚动区
- [x] 转写浏览层进入 / 退出不再挂起与恢复状态坞，且浏览层仍能独占全屏、退出后主屏回卷完好
- [~] 大量冻结输出滚屏期间状态坞保持在底部、不被顶走、不留残影 —— **帧内容一半已覆盖**（冻结批次在状态坞之上、状态坞不被复制进回卷）；**残影一半须真实终端人工验收**
- [x] 终端 resize 后状态坞位置与宽度正确
- [x] 无头帧断言覆盖：状态坞行出现在合成帧的正确位置与内容
- [x] `mvn test -Pquick` 与 `mvn test -Pphase16-smoke` 全绿（`-Pquick` 仅剩已知的顺序相关 flake `TerminalMarkdownRendererTest.wrapsWideMultiColumnTableInsideTerminalWidth`，单独跑绿，与本改动无关）
- [ ] **人工验收（Terminal.app 优先）**：滚轮快速滚动、长回合流式输出、resize 三种场景下状态坞不撕裂、不漂移、不重影。Terminal.app 无同步输出能力，撕裂在此最先显形——这是本 wave 的最高风险项 —— **未做，mock 终端按定义无法复现，待用户在 Terminal.app 上验收**
- [x] 文档同步：AGENTS.md 中关于 `BottomStatusBar` / JLine `Status` / 保留行数 / 滚动区的条目

## Blocked by

None - can start immediately

## Comments

### 实现记录（agent）

无头部分已落地，未提交。关键点：

- `BottomStatusBar` 退化为「持有 `StatusInfo` + 格式化成两行」的纯行来源（`rows(int width)`），不再持有 `Terminal`、不再写终端；`Status` / `suspend` / `restore` / `flushNow` / `prepareInputLine` / `finishInputLine` / `reservedRows` / `inputDockRow` 全部删除。
- `LiveRegionStrip.bindDock(IntFunction<List<AttributedString>>)` 取代 `bindReservedRows(IntSupplier)`；常量 `RESERVED_ROWS = 3` 删除。重绘区高度 = `terminalRows`，活区尾预算 = `terminalRows − 状态坞行数`。
- 状态坞只在**帧内最后一次**合成时附加，避免 `finishTurn` 的中途冻结路径把状态坞复制进[[冻结回卷]]。

**遗留的过渡期缺口（跨 issue，需知悉）**：`LiveRegionStrip.renderFrame()` 仍保留「`LineReader` 在读 → 走 `printAbove`、不重绘活区」的分支（该分支按 PRD 属 issue 05 删除面）。因此空闲态 `readLine` 期间状态坞**不会被重画**——`beforeInput()` 会在进入 `readLine` 前请求一帧把状态坞画在提示符上方，但此后的后台 push 输出会把它顶进回卷。issue 05（`CaptureDisplay` / 常驻 `readLine` / 删除 `printAbove` 分支）落地后该缺口按构造消失。这是排期顺序的后果，不是新设计决定；如果不能接受过渡期形态，04 与 05 应合并交付。
