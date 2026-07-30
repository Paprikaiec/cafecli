# 设计：`edit_file` 精确编辑工具

- 日期：2026-06-05（2026-06-12 合入评审决策：文件互斥锁前置、new_string 缺失语义、edits[] fast-follow）
- 状态：已通过设计评审，评审意见已合入
- 作者：Evan Wang（与 Claude 协作）
- 分支：`feat/edit-file-tool`
- 相关决策：`docs/adr/0001-per-file-mutation-lock.md`

## 动机

Cafecli 目前改代码只有整文件 `write_file`（`ToolRegistry.java:253`），没有局部编辑原语。在 SWE-bench 这类「改大仓库里某文件的几行」的场景，整文件重写会同时踩三个坑：

1. **烧 output token**：改几行要重发整个文件。
2. **幻觉 / 丢代码**：模型重写长文件时易改坏或删掉无关代码，直接让补丁 fail。
3. **撞 max_output_tokens 截断**：而当前 ReAct 循环把输出截断当成正常完成，截断后的补丁无声作废。

业界强 SWE-bench agent（Claude Code、Aider、SWE-agent）全部采用 diff / 精确替换式编辑。本设计引入一个最小可用、可量化、可单测的 `edit_file` 工具，作为 Cafecli tool use 能力的核心补强，并直接服务于即将进行的 SWE-bench-java A/B 评测。

参考实现：claude-code `FileEditTool`（精确字符串替换 + 唯一匹配 + 先读再改 + 新鲜度校验 + structuredPatch 回吐 + LSP 通知）。本设计**有选择地**借鉴，按 Cafecli 现状做了最小化裁剪。

## 目标 / 非目标

**目标**
- 提供 `edit_file(path, old_string, new_string, replace_all=false)` 精确局部编辑。
- 用「唯一匹配当前磁盘内容」保证编辑不落错位置。
- 复用现有 `writeFileObserver`（diff 渲染）与 `runPostEditLspHook`（诊断自纠正闭环）。
- 通过工具描述 + system prompt 引导模型「改已有文件优先 edit_file」。
- 提供完整单测，并可在 SWE-bench-java 上做 edit_file 开/关 A/B，量出 resolve rate Δ。

**非目标（本版明确不做）**
- MultiEdit（一次多处编辑）。
- 用 `edit_file` 新建文件（仍走 `write_file`）。
- 智能引号归一 / 模糊或空白容忍匹配。
- read-before-edit 守卫与 readState 子系统。
- 改动 `write_file` 的行为（只追加描述文案）。

## 已定决策（设计评审三个分叉）

1. **防盲改强度** → 仅靠「`old_string` 在当前磁盘内容里唯一匹配」（0 / 多匹配都报错）。**不**新建 readState、**不**强制先 `read_file`。
2. **功能面** → 单处替换 + `replace_all`。新建文件走现有 `write_file`，不做 MultiEdit。
3. **引导策略** → 新增工具 + 工具描述/ system prompt 引导；不改 `write_file` 行为。

## 工具面与 Schema

```
edit_file(path, old_string, new_string, replace_all=false)
```

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `path` | string | 是 | 目标文件路径，PathGuard 锁定项目根内 |
| `old_string` | string | 是 | 要替换的原文（需与文件内容完全一致，含缩进/空白/换行） |
| `new_string` | string | 是 | 替换后的文本（必须不同于 `old_string`） |
| `replace_all` | boolean | 否（默认 false） | 是否替换全部出现处（用于改名等） |

注意：Cafecli 工具入参经 `Map<String,String>` 传入（`asText()`），`replace_all` 在 executor 内解析布尔；`old_string`/`new_string` 原样取用。

## 匹配与替换语义（核心正确性）

对**文件当前磁盘内容**做精确子串匹配，`count = old_string 出现次数`：

| 情况 | 行为（返回给模型的错误字符串需可指导其改对） |
|---|---|
| `old_string` 为空 | 报错：「old_string 不能为空；新建文件请用 write_file」 |
| `new_string` key 缺失 | 报错：「new_string 参数缺失」（区分于显式空串——缺失是漏参，不得静默解释为删除） |
| `new_string` 为 `""` | 合法，表示删除匹配内容 |
| `old_string.equals(new_string)` | 报错：「old_string 与 new_string 相同，无改动」 |
| 文件不存在 / 非普通文件 | 报错：「文件不存在: <path>；新建请用 write_file」 |
| `count == 0` | 报错：「未找到要替换的内容，请确认 old_string 与文件完全一致（含缩进/空白/换行）」 |
| `count > 1 && !replace_all` | 报错：「old_string 在文件中出现 N 次，replace_all=false 无法定位。请补充上下文使其唯一，或设 replace_all=true」 |
| `count == 1`，或 `replace_all` | 执行 `content.replace(old, new)`（Java `String.replace` 为字面量替换、非正则），写回磁盘 |

补充约束：
- v1 **精确匹配原始字节**，不做 `\r\n` 归一、不做智能引号 / 模糊匹配（留作 fast-follow）。
- 写回前复用 `MAX_WRITE_FILE_BYTES`（`ToolRegistry.java:79`）大小护栏，对更新后内容校验。
- 所有失败路径返回错误字符串给模型（不向上抛异常），与现有工具风格一致；PathGuard 越界仍以 `PolicyException` 经 `doExecuteTool` 转为「策略拒绝」。

## 结果回吐（喂给模型自纠正）

成功时返回紧凑 diff，使模型能在结果里自检改动：

```
文件已编辑: <path>（替换 N 处）
@@
- <old_string，超长按字符/行截断>
+ <new_string，超长按字符/行截断>
```

- 用户侧彩色 diff 由已接好的 `writeFileObserver → renderer.appendDiff`（`Main.java:340`）自动渲染，结果文本不重复贴整段。
- LSP 诊断走**下一轮**已有的 `flushPendingLspDiagnostics`（`ToolRegistry.java:216`）→ `Agent.injectPendingLspDiagnostics`（`Agent.java:346`）注入，不混进本次结果。

## 集成点（全部复用现有设施）

- **diff 渲染**：执行成功后调用 `writeFileObserver.accept(path, new String[]{before, after})`（字段 `ToolRegistry.java:100`，写法见 `:284`）。
- **LSP 自纠正闭环**：执行成功后调用 `runPostEditLspHook(path, safe)`（`ToolRegistry.java:288` / 定义 `:873`）。
- **并发安全**：`write_file` / `edit_file` 的整个读改写执行体持**文件互斥锁**（同文件串行、异文件并行；JVM 静态锁表，key 用 `pathGuard.resolveSafe` 返回值；等锁用 `lockInterruptibly` + 获锁后复检取消；锁加在 executor 内部即 HITL 之后；LSP hook 在放锁后执行）。锁作为本工具的**前置任务**在同分支先行落地，详见 ADR 0001。原「读写分批并发」方案已废弃。
- **审计**：加入 `AUDIT_TOOLS`（`shouldAudit`，`ToolRegistry.java:1275`），与 write_file 一致写审计行。
- **HITL**：加入 `ApprovalPolicy.requiresApproval`，与 write_file 一样可被人工审批。
- **路径安全**：`pathGuard.resolveSafe(path)` 强制项目根内。

## Prompt 改动

- `edit_file` 工具描述：强调「改已有文件首选；old_string 必须唯一否则报错；改名用 replace_all；比 write_file 省 token、不误伤无关代码；新建文件用 write_file」。另加一句并发引导：「同一批次的多个 edit_file 看不到彼此的修改；同一文件多处修改请分轮顺序进行」（edits[] 落地后改为 pi 式 guideline）。
- `write_file` 工具描述追加一句：「对已有文件的局部修改请优先 edit_file」。
- agent 模式 prompt 资源（经 `PromptAssembler` 加载）加一句同义引导。
- **不改 write_file 的执行行为**。

## 实现路线

采用「方案 A」：新增独立 `edit_file` 工具，匹配/替换逻辑自带；把 write_file 与 edit_file 公共的「落盘 + observer + LSP hook」尾部抽成一个极小私有助手（例如 `persistEdit(displayPath, safe, before, after)`），二者复用。

- 工具在 `registerFileTools()`（`ToolRegistry.java:232`）内注册。
- 预计改动量：约 100 行实现 + 单测。
- 对 write_file 的改动仅限于：**行为等价地**抽取公共尾部供复用 + 描述文案追加；不改其任何执行语义。

## 测试

**单元测试（新增 `EditFileToolTest` 或并入 ToolRegistry 测试）**
- 唯一匹配 → 替换成功、文件内容正确、返回成功 + diff。
- `count == 0` → 报错且文件不变。
- `count > 1 && !replace_all` → 报错且文件不变。
- `count > 1 && replace_all` → 全部替换。
- `old_string == new_string` → 报错。
- `old_string` 为空 → 报错（指向 write_file）。
- 文件不存在 → 报错（指向 write_file）。
- 路径越界 → 策略拒绝。
- 更新后超 `MAX_WRITE_FILE_BYTES` → 报错。
- 成功路径：`writeFileObserver` 收到 `[before, after]`；`runPostEditLspHook` 被调用。
- `replace_all` 改名：每处出现都被替换。

**SWE-bench-java A/B**
- 同模型，edit_file 开 / 关（或 write_file-only 基线），跑同一批任务，量 **resolve rate Δ**。
- 从 NDJSON trace 统计：edit_file vs write_file 使用占比、max_output_tokens 截断次数，用于归因「为什么涨」。

## 风险与限制

- **CRLF 文件**：v1 精确匹配，若文件为 CRLF 而模型给 LF old_string 会匹配失败。缓解：错误信息提示「换行需一致」；fast-follow 增加按文件主导换行的一次容忍重试。
- **大幅整段替换**：模型若把 edit_file 当 write_file 用（超长 old/new），收益打折；靠 prompt 引导缓解，必要时再加软提醒（已作为评审备选，本版不做）。
- ~~**依赖读写分批 PR** 才能拿到「写串行」语义~~（已改为：文件互斥锁在同分支作为 Task 0 先行落地，edit_file 从诞生起持锁，无裸奔窗口期）。

## 后续（fast-follow，不在本版）

- CRLF / 行尾容忍匹配。✅ 本轮实现 CRLF/BOM + whole-line fuzzy（ADR 0002）。
- **MultiEdit（`edits[]` 事务化多处编辑，pi 式语义）**：所有 `old_string` 对**原始文件内容**全量校验（0 匹配 / 歧义 / 区间重叠都报错），全部通过后内存应用、单次写盘——全有或全无。**前置依赖**：内置工具的入参管道是 `Map<String,String>`（`asText()` 拍平，数组传不进 executor），需先开「原始 JSON 入参」通道（参照 MCP 工具的 `Function<String, ToolOutput>` invoker 模式）。落地时同步把工具描述里的「分轮顺序修改」引导改为 pi 式「同文件多处修改用一个 call 的多个 edits[] 条目」。✅ 本轮实现（编辑事务），见 ADR 0002/0003 与 plan。
- write_file 对已有文件大改的软提醒。
- 把 readOnly 之外的更多自描述元数据（needsAudit / needsApproval）收进 Tool，消除 `AUDIT_TOOLS`、`ApprovalPolicy` 等平行名单。
