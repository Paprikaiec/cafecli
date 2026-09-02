# AGENTS.md

Cafecli 的首读入口。**本文件只放「不写下来就会误判」的东西**；其余按下表分流。

| 想知道什么 | 去哪 |
|---|---|
| 术语的准确含义（活区 / 冻结回卷 / 工具块 / 取消键 / 保留窗口 / 策略守卫 / 任务…） | `CONTEXT.md` |
| 某个决策**为什么**是现在这样、否决过什么 | `docs/adr/`（0005 事件循环 · 0010 缩放 epoch + 保留窗口 · 0011 类型化块 · 0012 Hook · 0014 输入框常驻 · 0015 退役 Plan + Lanterna · 0016 输入框遗留 · 0017 输出分类学） |
| 配置项、读取顺序、环境变量、单模块行为细节 | `docs/agents-reference.md` |
| 某件事**落到哪一步了**、真机验收结果 | `.scratch/<feature-slug>/`（PRD + issues） |
| 有哪些文件、哪些类、什么签名 | 直接 `glob_files` / `grep_code` / `read_file`，不要查文档 |

## Language Policy

Think, reason, and write ALL output in English by default, no matter what language the user writes in — internal reasoning, session chat, and developer-facing docs alike. If the user explicitly asks for another language in one message (e.g.「用中文回答」), comply for that response, then return to English. 编辑既有文档时跟随该文件已有的语言。

## 信息优先级

1. 代码实际行为 > 2. `AGENTS.md` > 3. `CONTEXT.md` / `docs/adr/` > 4. `README.md` > 5. `ROADMAP.md`。`ROADMAP.md` 代表演进方向，**不代表已交付**。

## 项目快照

- `Cafecli`：面向商业使用的 Java Agent CLI，对标 Claude Code。已交付 21 期（清单见 `README.md`）。
- 进行中：会话内异步任务 substrate Wave 2（事件循环 / push / 虚拟线程 / 准入），见 ADR 0004–0007。
- Banner 版本 `v16.1.0` 与产物 `cafecli-1.0-SNAPSHOT.jar` 不一致是正常状态。
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
java -jar target/cafecli-1.0-SNAPSHOT.jar run --file issue.md --trace-file trace.jsonl
```

**`-Pphase16-smoke` 绿不代表渲染没问题**：屏幕真相判据不在这个 profile 里，要按名字单独跑——`ScreenTruthRegressionTest` / `InputBoxLiveRegionTest` / `ToolCallBlockTest` / `CaptureDisplayInterceptionGuardTest` / `HardTerminationCouplingTest`。

## 当前状态与已知边界

- **渲染侧的离线判据全绿，但真机确认普遍欠着**：ADR 0016 的人工验收 8 项只跑了 3 项（且修复后需复验），ADR 0017 的 issue 04 未跑。**不要把「离线全绿」读成「用户报的症状已修」**——屏幕模型是过滤器不是 oracle，真机回归环噪声大到单次跑没有分辨力（同一份代码副本数 28–118，n=11），A/B 必须成组跑。
- 路线图上未交付：容器 / VM 沙箱、MCP OAuth + sampling + server 自动重启。

## 维护约定

形成稳定协作规则 → 补进本文件「不变式」；实现细节 → `docs/agents-reference.md`；决策与被否决方案 → `docs/adr/`；进度与真机结果 → `.scratch/<slug>/issues/`。Issue 用本地 markdown（`docs/agents/issue-tracker.md`），五标签 triage（`docs/agents/triage-labels.md`），单上下文域文档布局（`docs/agents/domain.md`）。
