# Review: `edit_file` Tool Design Spec

- 日期：2026-06-10
- 评审对象：`docs/superpowers/specs/2026-06-05-edit-file-tool-design.md`
- 相关计划：`docs/superpowers/plans/2026-06-05-edit-file-tool.md`
- 结论：方向正确，但 spec 还需要补齐并发、参数、编码、联动文档和评测开关等约束，避免实现时留下隐性风险。

## 主要问题

### 1. 并发安全说法不成立

spec 认为 `readOnly=false` 能与“读写分批并发”协同，使 `edit_file` 串行独占。但当前 `ToolRegistry.Tool` 记录没有 `readOnly` 元数据，`ToolRegistry.executeTools()` 在多工具调用时会直接并发执行。

风险：

- 同一轮 LLM 返回两个 `edit_file` 修改同一文件时，两个工具可能同时读取同一份 before 内容。
- 两个替换各自成功后写回，后完成的写入会覆盖先完成的写入，形成 last-writer-wins。
- `old_string` 唯一匹配只能保证单次编辑定位正确，不能保证并发编辑组合正确。

建议：

- 把“写工具串行执行”列为 `edit_file` 的前置要求，或在 `edit_file` 内做按文件路径加锁。
- 若要依赖工具元数据，需要先扩展 `Tool` 定义，例如 `readOnly` / `mutatesWorkspace`。
- 增加并发测试：同一轮两个 `edit_file` 修改同一文件不同位置，最终文件必须同时包含两处修改，或第二个编辑明确失败并提示重读。

### 2. `new_string` 必填但运行时没有保证

schema 中 `new_string` 是必填参数，但现有工具入参会经 Jackson `JsonNode.asText()` 转成 `Map<String,String>`，工具执行器本身通常还需要做运行时校验。

风险：

- 如果实现把缺失的 `new_string` 当成空字符串，会把“参数缺失”误解释为“删除 old_string”。
- 删除文本本身又是合法用例，因此不能简单禁止空字符串。

建议：

- 明确区分“key 缺失”和“值为空字符串”：
  - `new_string` key 缺失：报错。
  - `new_string` 为 `""`：允许，表示删除匹配内容。
- 增加测试：missing `new_string` 报错且文件不变；empty `new_string` 可成功删除唯一匹配内容。

### 3. “精确匹配原始字节”表述不准确

spec 写的是“精确匹配原始字节”，但按现有 `write_file` 风格，`edit_file` 很可能使用 `Files.readString` / `Files.writeString` 实现。这是文本读取/写入语义，不是 byte-level patch。

风险：

- 非 UTF-8 文件或二进制文件会读取失败，不能按“原始字节”承诺处理。
- 读写时可能受默认 charset 影响。为避免平台差异，应显式使用 `StandardCharsets.UTF_8`。

建议：

- 将 spec 改为“UTF-8 文本精确子串匹配”。
- 读取和写入都显式指定 `StandardCharsets.UTF_8`。
- 增加测试：非 UTF-8 / 二进制内容读取失败时返回可理解错误，且文件不变。

### 4. 失败语义与审计语义需要更清晰

spec 说所有失败路径返回错误字符串，PathGuard 越界例外。但现有 `write_file` 对大小上限使用 `PolicyException`，由外层记录 policy deny 并返回“策略拒绝”。

风险：

- 如果 `edit_file` 超过 5MB 只是返回业务错误字符串，审计会记录为 allow，不符合 `write_file` 的资源上限语义。
- HITL / audit 中 `edit_file` 和 `write_file` 的行为不一致，会让安全边界难以解释。

建议：

- 明确 `MAX_WRITE_FILE_BYTES` 超限属于策略拒绝，抛 `PolicyException`。
- 普通业务错误，例如 0 匹配、多匹配、文件不存在，返回错误字符串。
- 增加 audit 测试：路径越界和编辑后超大小上限应记录为 deny；普通 0 匹配应记录为 allow 但结果是业务失败。

### 5. Prompt 和文档联动范围不足

spec 只提到更新 agent mode prompt。但仓库规则要求改工具集时联动 Agent / PlanExecuteAgent / SubAgent 提示词和文档。

风险：

- ReAct 模式会优先使用 `edit_file`，但 Plan-and-Execute 和 Multi-Agent worker 仍可能倾向 `write_file`。
- `AGENTS.md`、`README.md`、`docs/agents-reference.md` 中的核心工具数量和工具列表会过时。

建议：

- 同步更新：
  - `src/main/resources/prompts/modes/agent.md`
  - `src/main/resources/prompts/modes/plan.md`
  - `src/main/resources/prompts/modes/team-worker.md`
  - `AGENTS.md`
  - `README.md`
  - `docs/agents-reference.md`
- 在文档中把核心内置工具数量从 11 更新为 12，并把 `edit_file` 放在 `write_file` 旁边。

### 6. SWE-bench A/B 缺少关闭机制

spec 要做 `edit_file` 开 / 关 A/B，但没有定义如何关闭该工具。

风险：

- 评测时如果只是靠 prompt 不提 `edit_file`，工具仍在 schema 中，模型可能继续调用。
- 如果通过手工改代码关闭，A/B 不容易复现。

建议：

- 增加 feature flag，例如：
  - `CAFECLI_EDIT_FILE_ENABLED=false`
  - `-Dcafecli.edit_file.enabled=false`
- 或在 headless/eval 装配处提供明确的工具过滤开关。
- 更新 SWE-bench trace normalize，把 `edit_file` 归类为 `edit`，并单独统计 `edit_file` vs `write_file` 使用占比。

## 次要改进

### 1. 增加 TUI 工具调用展示适配

`ToolCallRenderer` 当前对 `write_file` 有专门标签和 path 提取，`edit_file` 会落到默认工具展示。

建议：

- 给 `edit_file` 增加标签，例如“精确编辑 N 个文件”。
- `extractKeyParam` 中把 `edit_file` 和 `write_file` 一样映射到 `path`。

### 2. 明确 diff 回吐截断预算

spec 说 old/new 超长会截断，但没有定义具体预算。

建议：

- 明确按字符和行双阈值截断，例如最多 20 行或 2000 字符。
- 截断后追加提示，例如 `...（diff preview truncated）`。
- 测试长 old/new 的结果不会把整段大内容回灌给模型。

### 3. 明确 occurrence count 是非重叠计数

Java `String.replace` 是字面量替换，实际匹配按非重叠 occurrence 处理。

建议：

- 在 spec 中明确 `countOccurrences` 必须按非重叠匹配计数，并与 `String.replace` 行为一致。
- 增加边界测试，例如内容 `aaaa`、`old_string=aa`，计数应为 2。

### 4. 补充 CRLF 和换行提示测试

spec 已把 CRLF 作为风险，但测试列表没有覆盖。

建议：

- 增加 CRLF 文件测试：old_string 使用 LF 时应匹配失败且提示换行需一致。
- 若未来做容忍重试，再增加 LF/CRLF 自动适配测试。

### 5. 补充 HITL / ApprovalPolicy 现有测试更新

仓库已有 `ApprovalPolicyTest`，不是必须新建单独测试类。

建议：

- 在现有 `ApprovalPolicyTest` 中加入：
  - `edit_file` 需要审批。
  - `edit_file` 是中危。
  - `edit_file` 风险描述不为空，且说明“精确替换/修改已有文件”。
  - dangerous tool 数量从 4 更新为 5。

### 6. 补充 tool schema 和 prompt nudge 测试

建议增加测试锁定工具定义：

- `getToolDefinitions()` 包含 `edit_file`。
- `edit_file` 参数包含 `path`、`old_string`、`new_string`、`replace_all`。
- required 包含 `path`、`old_string`、`new_string`。
- `write_file` 描述中提示已有文件局部修改优先 `edit_file`。

### 7. 考虑文件不存在错误文案

spec 对“文件不存在 / 非普通文件”统一返回“文件不存在”。这对目录路径、设备文件、权限问题不够准确。

建议：

- 错误文案改为“文件不存在或不是普通文件”。
- 新建文件仍明确指向 `write_file`。

## 建议落地顺序

1. 先更新 spec：修正并发、参数、编码、失败语义、A/B 开关和联动范围。
2. 更新 implementation plan：把并发策略和 feature flag 加进任务拆分。
3. 先写测试锁定工具 schema、参数校验、匹配语义和审计/HITL 行为。
4. 实现 `edit_file`。
5. 最后同步 prompt、TUI 展示和文档列表。
