# Inline TUI v16.1 端到端手测清单

> 配套 [`docs/inline-tui-pivot.md`](inline-tui-pivot.md) 的 §8 手测清单具体化。
> 每条用例自包含——给出启动命令 + 输入提示词 + 预期表现 + 通过判定。
> 失败现象优先说"看到了什么"而不是"猜原因"。

---

## 0. 启动前置

```bash
cd /Users/itwanger/Documents/GitHub/cafecli
mvn -q clean package -DskipTests
```

产物：`target/cafecli-1.0-SNAPSHOT.jar`，所有用例都基于这个 jar 启动。

清理副作用：跑完后用 `rm hello*.txt a.txt b.txt c.txt renamed.txt hello-test.txt 2>/dev/null` 清掉测试文件。

---

## 1. 默认 inline 模式启动（用例 1）

**启动**：
```bash
java -jar target/cafecli-1.0-SNAPSHOT.jar
```

**预期**：
- Banner 显示简洁的 `Cafecli v16.1.0` 开屏，不带右侧盒线边框
- 进入 JLine REPL，输入行由 LineReader 停在当前 transcript 位置，提示 `* `
- **不进 alternate screen**（退出后 banner、对话历史还留在 terminal scrollback 里）
- 输入行紧贴两行 inline dock：模式 / MCP / Skill 摘要行 + 模型 / phase / ctx / cwd 行（用例 15 详细验证）
- ReAct 请求发出后，输入区上方出现动态 `Thinking...` 面板；如果模型返回 reasoning delta，面板下方显示灰色 `> ...` 引用行，最终回复开始时面板被清理

**失败迹象**：
- 整屏被清空 → 误进了 alt screen
- 输出乱码 / 没颜色 → 终端不支持 ANSI，应该看到 stderr `⚠️ 终端不支持 ANSI`

---

## 2. 折叠块 — 单工具组（用例 2）

**输入**：
```
帮我读一下 README.md 的前 50 行
```

**预期**：Agent 调 `read_file` 时输出纯灰**单行折叠头**（无 `▎` 块色条），不含 emoji 或原地展开提示：
```
ReadFile(README.md)
```

回到空闲输入后按 **Ctrl+O**（不需要回车）：
- 进入全屏转写浏览层：工具 / 代码 / diff / 答案块默认展开，思考块默认只留折叠头（`思考 N 行`）
- 展开内容包含 `读取 1 个文件` + `└ README.md`（工具块默认展开）
- 层内按 **Ctrl+T** 一键展开 / 收起全部思考块；帮助行含 `Ctrl+T thinking`
- 主屏回卷里的折叠头不被覆盖或重打

再按 **Ctrl+O** / `q` / ESC：关闭浏览层并返回原输入行；Ctrl+T 态是无状态的，下次 Ctrl+O 重开又是思考折叠态。

**通过判定**：
- 默认就是折叠态（不是直接展开整段）
- Ctrl+O 浏览层不破坏主屏回卷或后续输出
- 进入 / 退出无颜色撕裂，鼠标追踪在退出时复位

---

## 3. 折叠块 — 多工具组（用例 3）

**输入**：
```
同时读取 pom.xml、AGENTS.md、ROADMAP.md 这三个文件
```

**预期**：单行折叠态（纯灰，无 `▎` 块色条）：
```
读取 3 个文件
```

Ctrl+O 打开浏览层后，看到三个文件路径都在 `└` 缩进列表里；关闭浏览层后主屏折叠头保持不变。

**注意**：Agent 必须在**同一轮**返回 3 个 tool call 才会聚合成一个折叠块。如果 Agent 拆成 3 轮（每轮 1 个），会出现 3 个独立折叠块——这是 LLM 的决策，不是 bug。

---

## 3.5 LLM 代码块内联与上限

**输入**：
```
帮我看一下 README.md，把内容用 markdown 代码块包起来给我
```

**预期**：代码块没有 `┌─` / `└─` 边框或四空格展示缩进。默认流显示纯灰 header（无 `▎` 块色条）+ 最多前 100 个源代码行：
```
code: markdown
... body ...
```

超过 100 行时追加 `… +N 行 (ctrl+o 查看全部)`；按 Ctrl+O 打开浏览层后显示完整代码。超宽代码行应在终端宽度处裁剪并带尾 `…`，不能 prose 折行破坏盒绘制图。

**通过判定**：
- 默认流最多显示 100 个源代码行，溢出计数准确
- 没有代码边框或四空格展示缩进
- Ctrl+O 浏览层显示完整代码且不改变主屏回卷

---

## 4. 转写浏览层与冻结回卷

**步骤**：
1. 跑完用例 2 / 3 任一个，记录主屏里的折叠头
2. 等 LLM 流式输出完成并回到空闲输入
3. 按 Ctrl+O 打开浏览层，使用滚轮、`j`/`k`、方向键、PgUp/PgDn、`g`/`G` 滚动
4. 按 **Ctrl+T** 展开全部思考块（再按收起），观察展开后仍能用 `G` 滚到思考尾部
5. 再按 Ctrl+O 关闭

**预期**：浏览层默认展开工具 / 代码 / diff / 答案块、思考块默认折叠；层内 Ctrl+T 一键展开 / 收起全部思考块，切换后浏览层高度会重算，滚动仍按行 / 页工作。主屏冻结回卷不被重绘，且不出现 `Block frozen` 提示。

**通过判定**：关闭后旧折叠头保持原样，没有错位 / 重影；Ctrl+T 只在浏览层内切换思考、层外不绑定；退出后再次 Ctrl+O 打开又是思考折叠态（无状态）。

---

## 5. HITL 单字符审批 — 批准（用例 4）

**前置**：
```
/hitl on
```

**输入**：
```
帮我在项目根目录创建一个 hello.txt，内容写 "hi"
```

**预期**：
```
⚠️  HITL 审批
┌─...┐
│ ⚠️  需要审批
│ 工具: write_file
│ 参数: ...
└─...┘
> [y] approve  [a] all  [n] reject  [s] skip  [m] modify
```

按 **`y`**（不要回车），立刻看到 `y` 回显 + 下一行继续执行。

**通过判定**：单字符响应，不需要回车；HITL 框紧凑、一屏内可见。

---

## 6. HITL 单字符审批 — 拒绝带原因（用例 5）

**输入**：
```
帮我在项目根目录创建一个 dangerous.sh，内容写 "rm -rf /"
```

按 **`n`**，立刻提示：
```
拒绝原因（可直接回车跳过）:
```
输入 `太危险了` 回车。

**通过判定**：
- 拒绝原因输入是 readLine（多字符），不是单字符
- Agent 收到 REJECTED 信号后回复"已拒绝"或重新规划

---

## 7. HITL 单字符审批 — 全部放行（用例 6）

**输入**：
```
帮我连续创建三个文件 a.txt b.txt c.txt，内容分别写 1 2 3
```

第一次提示按 **`a`**，应看到：`已批准 tool 范围`。

**预期**：后续两次 `write_file` 自动跳过 HITL 提示（`[HITL] write_file 已在本次会话中全部放行，自动通过`）。

**通过判定**：a.txt / b.txt / c.txt 都在项目根创建成功；只第一次 HITL 拦了。

---

## 8. HITL 单字符审批 — 修改参数（用例 7）

**输入**：
```
帮我创建一个 wrongname.txt 写入 "test"
```

按 **`m`**，提示：
```
当前参数: {"path":"wrongname.txt","content":"test"}
修改后的 JSON（空行 = 保留原参数）:
```
输入 `{"path":"renamed.txt","content":"modified"}` 回车。

**通过判定**：实际创建的是 `renamed.txt`，内容是 `modified`；`wrongname.txt` 不存在。

---

## 9. HITL 单字符审批 — 跳过（用例 8）

**前置**：`/hitl on`

**输入**：
```
帮我执行 echo hello
```

按 **`s`**。**预期**：Agent 收到 SKIPPED 信号，命令不执行。

---

## 10. 形态切换 — Lanterna 全屏（用例 9）

```bash
CAFECLI_RENDERER=lanterna java -jar target/cafecli-1.0-SNAPSHOT.jar
```

**预期**：进 alternate screen，三栏 Lanterna 窗口（文件树 + 对话流 + 状态栏 + 底部输入栏）。

- 在输入栏问问题，输出在中间对话流面板
- HITL 弹 Lanterna 模态框（Yes / No / Cancel 按钮）
- 退出后 scrollback 干净（alt screen 特性）

**通过判定**：UI 完全替换为窗口形态，不是 inline 滚动。

---

## 11. 形态切换 — 兼容旧 CAFECLI_TUI（用例 10）

```bash
CAFECLI_TUI=true java -jar target/cafecli-1.0-SNAPSHOT.jar
```

**预期**：
- stderr 出现 `⚠️ CAFECLI_TUI=true 已废弃，请改用 CAFECLI_RENDERER=lanterna`
- 行为等同用例 10（Lanterna 全屏）

---

## 12. 形态切换 — Plain 兜底（用例 11）

```bash
CAFECLI_RENDERER=plain java -jar target/cafecli-1.0-SNAPSHOT.jar
```

**输入**：
```
读取 ROADMAP.md
```

**预期**：
- 工具调用输出**直接展开**（`📖 读取 1 个文件 / └ ROADMAP.md`），**没有 ⏵ 折叠提示**
- HITL 提示是多行 readLine 风格（`> ` 后回车确认），不是单字符
- 没有底部状态栏

**通过判定**：等价 v15 行为。

---

## 13. 禁色（用例 12）

```bash
NO_COLOR=1 java -jar target/cafecli-1.0-SNAPSHOT.jar
```

**预期**：
- 所有 ANSI 颜色消失（"思考过程"标题、subtle 灰、emphasis 粗体都退化为纯文本）
- 但布局结构（diff 的 `Update <path>  +A -D` 头、右对齐行号槽、`+`/`-` 标记、代码 / diff 溢出标注）保留；块色条 `▎` 与 `@@` hunk 头均已退役
- 文本颜色消失；底部 dock 仍使用光标控制占底部三行

---

## 14. 未知 RENDERER 值的回退（用例 13）

```bash
CAFECLI_RENDERER=weird java -jar target/cafecli-1.0-SNAPSHOT.jar
```

**预期**：stderr `⚠️ 未识别的 CAFECLI_RENDERER='weird'，回退到 inline`，正常进 inline 模式。

---

## 15. dumb 终端自动降级（用例 14）

```bash
TERM=dumb java -jar target/cafecli-1.0-SNAPSHOT.jar
```

**预期**：stderr `⚠️ 终端不支持 ANSI，inline 模式回退到 plain`，行为等同用例 12。

---

## 16. 底部状态栏数据可见（用例 15）

**启动**：默认 inline 模式

**输入**：
```
帮我读 README.md
```

**预期**：等待输入时，LineReader 输入行紧贴两行 inline dock，中间不能出现大段空白：
```
*                                                       message / @path / @image
 YOLO Ctrl+Y to enable HITL                         MCP 4/4 · Skill 2/2
 Auto Model · glm-5.1  idle  ctx ░░░░░░░░ 0% (...)  ~/project
```

- 模型名跟当前 `/model` 一致
- MCP 与 Skill 摘要跟当前配置一致，形如 `MCP 4/4`、`Skill 2/2`
- token 计数在 LLM 响应回来后跳动（如 `1.3k/200.0k`）
- 任务运行时阶段从 `idle` 切到 `react` / `plan` / `team`，流式响应期间 elapsed 持续增长
- HITL / YOLO 文案反映 `/hitl on/off` 与 Ctrl+Y 状态

**通过判定**：dock 紧贴 prompt 且位置稳定；输入文字时不与状态区冲突，输入提交后状态区和后续空白被清掉。

**已知小瑕疵**：少数终端 resize 后可能出现一次轻微重绘闪烁，继续输入或下一次状态更新会恢复。

---

## 17. HITL 状态联动状态栏（用例 16）

**步骤**：
1. 启动后 dock 显示 `YOLO Ctrl+Y to enable HITL`
2. 按 Ctrl+Y，确认立即变为 `HITL Ctrl+Y for YOLO`
3. 再按 Ctrl+Y，确认恢复 YOLO；并分别用 `/hitl on` / `/hitl off` 验证同一路径

**预期**：每次切换都立即刷新，不需要等待下一次 LLM 调用。

---

## 18. 行内 diff — 修改已有文件（用例 17）

**前置**：`/hitl on`

**输入**：
```
把 ROADMAP.md 第一行改成 "# Cafecli 路线图（v16.1 测试）"
```

HITL 按 `y` 通过。**预期**对话流里出现 Claude-Code "Update" 风格 diff（行号槽右对齐，宽度按文件最大行号，示例用 3 宽）：

```
Update ROADMAP.md  +1 -1
  1  - # Cafecli ROADMAP（原标题）
  1  + # Cafecli 路线图（v16.1 测试）
  2    ...（上下文行）
```

- `-` 行红色（256 色背景铺满整行 / 16 色回退为红色前景）
- `+` 行绿色（256 色背景铺满整行 / 16 色回退为绿色前景）
- 上下文行灰暗、无背景
- 头部 `Update <path>  +A -D` 为纯灰，无 `▎` 块色条；无 `@@` hunk 头

**通过判定**：diff 出现在工具结果之后；改回原文也能正确 diff。

**清理**：
```
git checkout ROADMAP.md
```

---

## 19. 行内 diff — 新建文件（用例 18）

**输入**：
```
帮我创建一个 hello-test.txt，内容写 "line 1\nline 2"
```

HITL 通过后**预期**（新建文件行号从变更后文件计，无 `@@` hunk 头）：
```
Update hello-test.txt  +2 -0
  1  + line 1
  2  + line 2
```

**清理**：`rm hello-test.txt`

---

## 20. 行内 diff — 二进制 / 大文件 fallback

**步骤**：
1. 让 Agent 用 `write_file` 写入超大内容（比如 10000 行的列表）
2. 不会触发，因为 5MB 上限会先拒绝

**改测**：让 Agent 覆盖一个二进制文件（不太可能由 LLM 自然触发）。

**预期**：如果 before 内容读不出（二进制 / 编码错），diff 会按"新建文件"形态展示——这是 ToolRegistry.write_file lambda 里 `before == null` 的兜底分支。

---

## 21. /config palette — inline 模式（用例 19）

**输入**：
```
/config
```

**预期**：浮起选择列表：
```
┌─ 配置 / config ─
│ ▶ [1] 模型: glm-5.1 / zhipu
│   [2] 默认 Provider: glm
│   [3] HITL: OFF
│   [4] Skill 启用数: 3
│   [5] 渲染器: InlineRenderer
│   [6] 配置文件: ~/.cafecli/config.json (只读视图，编辑请用编辑器)
└─ ↑↓ 切换  Enter 确认  Esc 取消  数字键直选
```

测试交互：
- **↑↓** 移动选中（`▶` 跟随）
- **j / k** 同上
- **数字 `3`** 直选第 3 项（HITL）
- **Enter** 确认当前选中
- **Esc** / **q** 取消

每种确认后：
- palette 应**完全消失**（用 `[<n>A[J` 清屏到末尾）
- 对话流出现一行提示：`💡 切换 HITL: /hitl on / /hitl off` 等
- 取消则出现 `(已关闭)`

**通过判定**：palette 不留痕迹；后续输入照常。

---

## 22. /config palette — Lanterna 模式（用例 20）

```bash
CAFECLI_RENDERER=lanterna java -jar target/cafecli-1.0-SNAPSHOT.jar
```

TUI 输入框输入 `/config`。

**预期**：弹出 Lanterna 模态选择列表（不是 inline 浮起 palette），选项内容相同。选中后 inline 模式下的提示文案应该出现在 CenterPane（对话流）中。

---

## 23. CAFECLI_NO_STATUSBAR 禁用状态栏

```bash
CAFECLI_NO_STATUSBAR=true java -jar target/cafecli-1.0-SNAPSHOT.jar
```

**预期**：
- 冻结回卷、Ctrl+O 浏览层、HITL 单字符、`/config` palette 等其它 inline 特性照常
- 但 prompt 下方**没有 inline 状态区**
- 输入提示 / Agent 输出可以一直占满整屏，不会被状态栏占位压缩

---

## 24. 终端尺寸过小自动降级

**手动操作**：把终端窗口拖到 `< 5 行` 或 `< 20 列`。

```bash
java -jar target/cafecli-1.0-SNAPSHOT.jar
```

**预期**：`TerminalCapabilities.supportsScrollRegion` 返回 false，inline 模式禁用状态栏，但其它特性不变。

---

## 25. Ctrl+C 中断与状态栏

**步骤**：
1. 默认 inline 模式启动
2. 输入一个长任务：`帮我把 ROADMAP.md 全文逐行翻译成英文`
3. LLM 流式输出中按 **Ctrl+C**

**预期**：
- 任务取消，对话流出现 `⏹️ 已请求取消当前任务。`
- 状态栏 `elapsed` 停止刷新
- 提示符回到底部 dock 的 `* `
- 整体不卡死

---

## 26. /clear 清屏不影响状态栏

**输入**：
```
/clear
```

**预期**：
- 对话历史清空；下一次等待输入时，prompt 下方状态区仍会出现
- 保留窗口与尚未提交的活区尾一并清空；之后 Ctrl+O 打开的浏览层为空

---

## 27. 退出清理

按 Ctrl+D 或输入 `/exit`。

**预期**：
- 当前输入期状态栏被清掉
- 输出 `👋 再见!`
- 进程正常退出
- terminal scrollback 里完整保留对话历史（inline 模式特性）
- Lanterna 模式则没有这个保留（alt screen 特性）

---

## 通过总判据

每条用例都满足"预期"且没有"失败迹象"，则 v16.1 inline TUI 改造视为通过。

**已知不在测试范围**：
- 流式输出与状态栏并发刷新的撕裂（极小概率，肉眼几乎不可见，不阻断任务）
- 旧 Windows cmd / PuTTY 等不适合 ANSI 光标控制的终端：用 `CAFECLI_NO_STATUSBAR=true` 即可

---

## 测试运行记录模板

跑完一遍后填到 commit message 或 PR body：

```
v16.1 inline TUI 端到端测试结果（YYYY-MM-DD 终端：iTerm2 / macOS Terminal / xxx）：

✅ 1 默认 inline 启动
✅ 2 单工具折叠头 + Ctrl+O 浏览层
✅ 3 多工具折叠头 + 全展开
✅ 4 浏览层滚动 + 冻结回卷不变
✅ 5 HITL y
... （列举每条）
❌ N 行内 diff 修改已有文件 — 看到 X，期望 Y
```

任何 ❌ 项需要 issue 跟进，不能直接 ship。
