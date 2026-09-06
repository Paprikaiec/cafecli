# AGENTS.md

Cafecli 的首读入口。**本文件只放「不写下来就会误判」的东西**；其余按下表分流。

| 想知道什么 | 去哪 |
|---|---|
| 术语的准确含义（活区 / 冻结回卷 / 工具块 / 取消键 / 保留窗口 / 策略守卫 / 任务…） | `CONTEXT.md` |
| 某个决策**为什么**是现在这样、否决过什么 | `docs/adr/`（0005 事件循环 · 0010 缩放 epoch + 保留窗口 · 0011 类型化块 · 0012 Hook · 0014 输入框常驻 · 0015 退役 Plan + Lanterna · 0016 输入框遗留 · 0017 输出分类学 · 0018 快照绑定） |
| 配置项、读取顺序、环境变量、单模块行为细节 | `docs/agents-reference.md` |
| 某件事**落到哪一步了**、真机验收结果 | `.scratch/<feature-slug>/`（PRD + issues） |
| 有哪些文件、哪些类、什么签名 | 直接 `glob_files` / `grep_code` / `read_file`，不要查文档 |

## Language Policy

Think, reason, and write ALL output in English by default, no matter what language the user writes in — internal reasoning, session chat, and developer-facing docs alike. If the user explicitly asks for another language in one message (e.g.「用中文回答」), comply for that response, then return to English. 编辑既有文档时跟随该文件已有的语言。

## 信息优先级

1. 代码实际行为 > 2. `AGENTS.md` > 3. `CONTEXT.md` / `docs/adr/` > 4. `README.md` > 5. `ROADMAP.md`。`ROADMAP.md` 代表演进方向，**不代表已交付**。

## 项目快照

- `Cafecli`：面向商业使用的 Java Agent CLI，对标 Claude Code。已交付 21 期（清单见 `ROADMAP.md`——`README.md` 自公开发布起改为用户视角，不再是分期变更日志）。
- 进行中：会话内异步任务 substrate Wave 2（事件循环 / push / 虚拟线程 / 准入），见 ADR 0004–0007。
- 版本号自公开发布起走 semver：banner、`pom.xml`、产物 `cafecli-1.0.0.jar` 三者一致，**与分期编号解耦**（分期叙事只留在 `ROADMAP.md`）。旧 banner `v16.1.0` 是期号沿用，已退役。
- 品牌迁移已完成：包名 `com.cafecli`，配置优先 `~/.cafecli` / `.cafecli` / `CAFECLI_*` / `-Dcafecli.*`；旧的 `paicli` 形式仅作兼容回退，解析集中在 `config/ConfigHome.java`。

## 架构：唯一一条执行路径

ReAct（`agent/Agent.java` + `ForegroundAgentSpawner.java`），共享 `ToolRegistry` / `MemoryManager` / `SnapshotService`。核心内置工具 12 个（`read_file` / `write_file` / `edit_file` / `list_dir` / `glob_files` / `grep_code` / `execute_command` / `create_project` / `search_code` / `web_search` / `web_fetch` / `revert_turn`）+ 异步任务 3 个（`spawn_agent` / `send_message` / `task_output`）+ MCP 动态工具（`mcp__{server}__{tool}`）。交互 REPL 是事件循环（ADR 0005，`AgentSession` + `cli/InteractiveRepl.java`，`Main` 只做装配）；无人值守走 `run --file`，不引入事件循环 / 泵 / push。

运行前提：Java 21+（虚拟线程）/ Maven；可选 `ripgrep`；至少一个 API Key（`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY`）。

## 不变式（违反即缺陷）

这些是**代码里成立、但从代码结构或通用经验推不出来**的约束。

### 权限

- **守卫绝对**：`PathGuard` / `CommandGuard` 的拒绝谁都抬不动——用户批准不了，[[生命周期 Hook]] 的 `ALLOW` **只免除本次 HITL 审批**、守卫仍在执行时运行。**这是有意偏离 Claude Code**（其 hook allow 绕过更多权限层），不要按 Claude Code 的语义推理。
- 拦截顺序：`HitlToolRegistry`（含 PreToolUse 路由）→ `ToolRegistry` → `PathGuard` / `CommandGuard`。
- 多 Hook 租户组合取最严：BLOCK > ASK > ALLOW > CONTINUE。`BLOCK` 是**模型反馈级**阻断（回灌原因续跑），不是安全拒绝。四个事件默认全关，靠系统属性开启。

### 记忆

- 长期记忆**只**经 `/save` 或用户明确要求写入，**不自动提取事实**；只存跨会话稳定事实，默认项目级作用域。
- 两道压缩不是一件事：`ContextCompressor` 压 shortTermMemory；`ConversationHistoryCompactor` 压 conversationHistory——**后者才是防 window 超限的那道**。

### 工具

- 并行调用一律走 `ToolRegistry.executeTools()`，不手写 for-loop；默认并发 4，结果保持原始顺序。
- 代码定位默认 `glob_files` → `grep_code` → `read_file` 行段。`search_code`（RAG 语义）只在关键词不明确 / 常规搜索无果时用，**不是精确定位的首选**。`read_file` 裸读 >256KB 直接报错，改用 `offset` / `limit`。
- 会话内多 Agent **只有一个模型**：`spawn_agent` / `send_message` / `task_output`。`AgentOrchestrator` / `/team`（ADR 0004）与 Plan-and-Execute / `/plan`（ADR 0015）**已从树上删除**，别按它们还存在来推理。
- DeepSeek V4 / Kimi thinking 模式下，assistant tool-call 消息的 `reasoning_content` **必须**随下一轮请求历史带回；其他 provider 只写日志 / 展示。

### 快照（术语见 `CONTEXT.md`「快照」，理由见 ADR 0018）

- **快照仓库必须 bare 创建**：`Git.init().setDirectory(projectRoot)` 与 `Repository.create(false)` 都会往项目根写一个指回快照仓库的 gitfile——项目根的 `.git` 若本身是 gitfile（linked worktree / submodule）就被**覆盖**，静默改写历史归属。副作用属于「创建 gitDir 在工作树之外的**非 bare** 仓库」这件事，**不是 `InitCommand` 独有的**（已实测证伪，别再走一遍）。
- **工作树按调用绑定**：每次操作现场 `setGitDir().setWorkTree()`，`core.worktree` 永不落 config。gitDir 是仓库的属性，工作树是调用的属性。
- 判据必须**读 gitfile 字节**：「快照跑通了」「`/snapshot` 有输出」在 `.git` 已被换掉时全都为真，间接判据对这条 bug 结构性失明。
- **恢复期围栏是真实路径判据，且只有一处实现**：恢复走 `PathGuard`（`resolveSafe` 向上找最近存在祖先解析软链），不要再写第二套。`resolve().normalize()` 是纯字符串操作，随后的 `Files.write` / `createDirectories` 都跟随软链——路径字符串留在根内、实际落点在根外。与写工具的差别只在**末段**：写工具解析末段是对的，恢复不能（落点就是末段本身，跟着走就是写穿）。
- **恢复按 git file mode 分支**：mode 120000 的 blob 内容**就是链接目标字符串**，丢掉 mode 就会把它当普通文件字节写出去，得到一个内容为链接目标路径的普通文件——而不是重建那条链接。同理 gitlink 的 object 不是文件内容。
- 同上，判据必须**读根外那个受害文件的字节**：越界发生时 `success=true`、`restoredFiles` 里只有项目内相对路径、屏幕上没有任何异常。

### 渲染（术语见 `CONTEXT.md`「终端渲染」，理由见 ADR 0010 / 0014 / 0016 / 0017）

- **活区单写者**：`RenderLoop` 是活区帧的唯一写者，生产者 / 模型 / 任务 / 输入线程一律只投事件。**渲染线程从不读终端**——渲染线程上任何终端读取都是死锁缺陷。**活区的判据必须读屏**（`TerminalScreenModel`）：「画了再原地擦掉」与「留在屏上被滚走」**字节完全相同**，所以字节断言对活区残留结构性失明。
- **终端按键只有一个消费者**：常驻 `readLine`。`Main.java` 与 `InteractiveRepl.java` 里 `terminal.reader()` 与 `enterRawMode()` 必须为**零次**（CI 直接读这两个源文件断言）；模态按[[模态租借]]合法自持终端，不在此列。
- **块类型来自类型化事件**（`beginReasoning` / `beginAnswer` / `appendToolCalls` / `appendToolResults` / `appendCodeBlock` / `appendDiff` / `appendCommandOutput`）。**不要**从渲染出来的文本嗅前缀反推块类型。
- **缩放走 [[destructive resize reset]]**：擦可视屏 + 擦保存回卷 + 归位光标（顺序是契约），再按 [[bounded replay window]] 重画。「算出该擦多远」那条路（相对擦除 / 底边钉位 / reflow 行数算术 / 幽灵行跟踪）**已被真机实测证伪并整体删除，不留开关**——被证伪的具体形状见 ADR 0010 附录，别再走一遍。
- 交互主路径不要新增裸 `System.out.println`，走 `Renderer.stream()`；system `Terminal` 一律从 `TerminalCapabilities.systemTerminalBuilder()` 构造，不要 `TerminalBuilder.builder().system(true)`。
- 未识别的 `/xxx` 在 CLI 层直接报「未知命令」，**不回退给 Agent**（`/plan` 是例外：打印退役墓碑，ADR 0015）。

### 数字

**3（工具结果视觉行）/ 5（运行中输出尾行）/ 20 / 12（短终端阶梯）四个数都是拍的，不是量的。** 不要当实测值引用，也不要从它们推导别的常数。测量归 `.scratch/unified-output-taxonomy/issues/04`。

## 改动联动

| 改了什么 | 必须同步 |
|---|---|
| 触到上面任一不变式 | 本文件 + 对应 ADR（新决策 / 被否决方案）|
| 命令入口 | `Main.java` + `CliCommandParser.java` + 测试 + `README.md` |
| 工具集 | `ToolRegistry.java` + `prompts/modes/agent.md` + 文档 |
| 模型 / 接口 | 对应 Client + `LlmClientFactory.java` + `.env.example` |
| HITL / 策略 / MCP / Memory / Web / Embedding | 见 `docs/agents-reference.md` 对应节 + 测试 |

不提交 `.env` / 真实 API Key / `target/` 产物。保持可读，不过度抽象。

## 验证

```bash
mvn clean package                            # 默认跳过测试，优先产出可手工验收 jar
mvn test -Pquick                             # 常规回归
mvn test -Pphase16-smoke                     # inline 渲染
mvn test -Dtest=XxxTest -DskipTests=false    # 针对性（注意 -DskipTests=false）
java -jar target/cafecli-1.0.0.jar run --file issue.md --trace-file trace.jsonl
```

**`-Pphase16-smoke` 绿不代表渲染没问题**：屏幕真相判据不在这个 profile 里，要按名字单独跑——`ScreenTruthRegressionTest` / `InputBoxLiveRegionTest` / `ToolCallBlockTest` / `CaptureDisplayInterceptionGuardTest` / `HardTerminationCouplingTest`。

**测试判据不能取决于「谁在跑测试」**（每条都实测过；宿主终端与加载顺序那两条由 `RenderTestIsolationGuardTest` 钉住）：

- **宿主终端**：`TerminalCapabilities` 的 synchronized-output(DEC 2026) / osc8 探测在 `terminal.getType()` 不认识时会回退读进程的 `TERM_PROGRAM`——那是**跑 Maven 的那个终端**，不是测试里 mock 出来的 `Terminal`。测试期由 pom 的 `${test.argLine}` 钉死这两项，三份 surefire 配置都必须走它。实测 `InlineRendererTest` 在 `TERM_PROGRAM=ghostty` 下 5 红、unset 下全绿。
- **加载顺序**：`AnsiStyle.ENABLED` 是 `static final`，每个 JVM 只算一次，所以**谁先加载 `AnsiStyle` 谁决定整个 fork 的色彩档位**。测试里不要写 `paicli.render.color` 这个全局（两个方向都会污染：自己赢就把别人染红，别人赢就把自己染红，先手时还会让 `AnsiStyleTest` 两条 `assumeTrue` 静默 skip）。要纯文本就剥 ANSI（`AttributedString.fromAnsi`），要宽度就按显示列数算，别用 `String.length()`。
- **也不要**图省事把 `paicli.render.color` 一起钉进 `${test.argLine}`：实测会让 `ScreenTruthRegressionTest` 两条变红 + `AnsiStyleTest` 两条静默 skip。
- **`TERM` 同属这一类，但钉在 CI 的 job env 里，不在 pom 里**（`AnsiStyle.ENABLED` 与 `TerminalCapabilities.supportsAnsi()` 都读它：unset / `xterm` → 开，`dumb` → 关）。实测 `-Pquick` 在 `TERM=dumb` 下是 **101 fail + 18 error + 2 skip**，`TERM=xterm` 下全绿。别把 `.github/workflows/ci.yml` 里那行 `TERM: xterm` 当冗余清掉。
- **线程调度同属这一类**（管道流驱动的 REPL rig：`InteractiveReplTest` / `CancelKeyTableTest` / `SessionInterruptGuardTest`）。**无条件的只有一条**：`repl.run()` 一律跑后台线程，不在测试线程上同步调——「先把按键全塞进管道、再同步跑 REPL」这个形状把「我敲的东西已经处理完了」**假设**成了字节顺序，正是它烧掉了一次 6 小时的 CI。另两条**只管拿 Ctrl+D 当收场的用例**，不管拿按键本身当被测对象的：收场的 Ctrl+D 要先等 `SessionInterruptGuard.turnIsRunning()` 转假（**不是 `session.isIdle()`，那只是它的一半**——另一半是注册表里的前台令牌），退出要用**断言**确认、不能是裸 `join(5_000)`（实测同一份竞态代码，裸 join 下是 13 绿 31.8s，换成断言之后才变成 4 条点名）。**`CancelKeyTableTest` 的取消键表整类是例外**：`ctrlDDuringATurnDoesNothing` 必须在回合运行中按下 Ctrl+D、且断言循环**没有**退出——按后两条去改它，等于删掉 `InteractiveRepl:337` 唯一的覆盖（`.scratch/ci-and-test-quarantine/issues/03`）。

## 当前状态与已知边界

- **渲染侧的离线判据全绿，但真机确认普遍欠着**：ADR 0016 的人工验收 8 项只跑了 3 项（且修复后需复验），ADR 0017 的 issue 04 未跑。**不要把「离线全绿」读成「用户报的症状已修」**——屏幕模型是过滤器不是 oracle，真机回归环噪声大到单次跑没有分辨力（同一份代码副本数 28–118，n=11），A/B 必须成组跑。
- **快照劫持 worktree 的 `.git`：已修复**（ADR 0018，`fix/snapshot-gitfile-hijack`）。bare 创建 + 按调用绑定，两种 `.git` 形态由 `SnapshotDoesNotTouchProjectGitTest` 逐字节钉住；已被咬过的项目由 `HijackedProjectGit` 只读检测 → 停用快照 + 打印 `git worktree repair` 指引（不自动改写）。**已被咬过的目录本身仍是坏的**——修复阻止新的劫持，不修既有的那个，要用户在主仓库那侧 repair。
- **快照恢复的符号链接逃逸：已修复**（`.scratch/snapshot-restore-path-containment/`）。恢复期改用 `PathGuard` 真实路径围栏 + 按 file mode 分支，`SnapshotRestoreStaysInsideProjectRootTest` 逐字节钉住（6 条里 4 条在修复前红）。但**那 4 条红是三层一起拿掉测出来的**：单层变异实测（2026-09-04）只有 file mode 分支那一层能红，把 `RestoreFence.contained()` 改成无条件放行、或删掉末段 `Files.isSymbolicLink` 跳过，30 条快照 / 围栏测试全绿。**别按「围栏被判据钉死了」去重构那三层**，缺口见 `.scratch/snapshot-restore-path-containment/issues/03`。**副作用**：目标指向根外的软链条目不再重建，也不再计入 `restoredFiles`——围栏的职责是不越界，不是替用户改写工作区布局。
- **`NO_COLOR=1` 下 `-Pquick` 是红的：2 红 + 2 静默 skip（已实测 2026-09-03，未修）**。`NO_COLOR` 让 `AnsiStyle.ENABLED=false`，输入框走 `InputBoxChrome.Palette.PLAIN` 那一档——横线由 `─` 退化成 ASCII `-`（那一档的承诺是「一个转义字节都不产生」，是有意设计），而 `ScreenTruthRegressionTest` 里 `theInputBoxIsARuleTheInputRowAndARuleOnTheUsersScreen` / `anInputFrameCapturedAtAnotherWidthComposesNothingAndCannotOverrunTheDock` 把 `─` 写死了；同时 `AnsiStyleTest` 两条 `assumeTrue(isEnabled())` 静默 skip。**产品行为没坏，坏的是判据**：修法是照 `InlineRendererTest:360` 的成例，让测试从 `InputBoxChrome.Palette` 推出该期望哪根横线，而不是写死字面量。CI 若设了 `NO_COLOR` 会直接红。
- 路线图上未交付：容器 / VM 沙箱、MCP OAuth + sampling + server 自动重启。
- **项目级配置的信任模型不对称**：项目级 hook 过信任闸（SHA-256 指纹钉住 + 确认器，ADR 0012 决策 3），项目级 `mcp.json` 不过。**别按「两者对称」推理**。这是当前行为，设计待定，细节与选型见 `.scratch/mcp-project-config-trust/`。

## 维护约定

形成稳定协作规则 → 补进本文件「不变式」；实现细节 → `docs/agents-reference.md`；决策与被否决方案 → `docs/adr/`；进度与真机结果 → `.scratch/<slug>/issues/`。Issue 用本地 markdown（`docs/agents/issue-tracker.md`），五标签 triage（`docs/agents/triage-labels.md`），单上下文域文档布局（`docs/agents/domain.md`）。
