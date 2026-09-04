# PRD: edit_file 编辑事务（edits[] 事务化多处编辑）+ 工具入参 JsonNode 通道

Status: ready-for-agent
Date: 2026-06-16
Branch: feat/edit-file-tool
Decision record: ADR 0002（edit_file 偏离 pi）、ADR 0003（工具入参 JsonNode 通道）；承接 ADR 0001（文件互斥锁）
承接: `.scratch/edit-file-tool/PRD.md`（单编辑 + 文件互斥锁，已落地）的 fast-follow

## Problem Statement

Cafecli 的 `edit_file` 目前只能一次替换一处（顶层 `old_string`/`new_string`）。要在同一文件改多处时，Agent 只能发出**工具批次**里多个 `edit_file` 调用——而工具批次默认并行、批内兄弟调用互不可见：第二个调用的 `old_string` 基于旧内容生成，常常匹配失败或落到错位置；即便文件互斥锁保证了串行落盘，模型也看不到上一处改动，仍会踩坑。

同时 `edit_file` 对原始字节精确匹配：CRLF 文件遇到 LF 的 `old_string`、带 BOM 的文件、或模型把智能引号/尾随空白写得略有出入，都会直接 0 匹配失败——这是真实仓库里最高频的匹配失败来源。

底层还有个前置障碍：内置工具入参经 `JsonNode.asText()` 拍平成 `Map<String,String>`，数组值会被压成空串，`edits[]` 这种结构化参数根本传不进工具执行器。

## Solution

引入**编辑事务**：一次 `edit_file` 调用携带一个 `edits[]` 数组，所有 `old_string` 都对**同一份原始文件内容**匹配；全量校验（0 匹配 / 歧义 / 区间重叠都报错）通过后，内存一次性应用、单次写盘——**全有或全无，不留半成品**。同文件多处修改从此是一个 tool call 内的原子批，而不是多个互不可见的工具批次调用。

匹配层加三道容忍（以 pi agent 的 `edit` 工具为蓝本，但收敛得更安全）：CRLF 与 BOM 做**无损 round-trip**（在 LF 空间匹配、写盘还原原始行尾与 BOM）；精确匹配失败时对**单独一行**做模糊容忍（智能引号/破折号/尾随空白/全角空格/NFKC），且**只改命中的那一行、文件其余字节保持不变**——不像上游那样一处模糊命中就把整文件静默归一。

前置打通「原始 JSON 入参通道」：内置工具执行器改为接收解析后的 `JsonNode`，遗留标量工具用一层适配器过渡，行为字节级不变。

对用户与 Agent 而言：同样的预算下一次提交更多处修改、补丁不再因行尾/引号细节无声失败、改坏整批时整体回退、终端实时看到 git 风格的合并 diff。

## User Stories

1. As an LLM agent, I want 把同一文件的多处修改压进一次 `edit_file` 调用的 `edits[]`，so that 它们原子生效、不被工具批次的并行与互不可见坑到。
2. As a Cafecli 用户, I want 一次调用里任一处匹配失败/歧义/区间重叠时整批都不写盘，so that 我的文件不会留下半成品。
3. As an LLM agent, I want 每条 edit 的 `old_string` 都对原始文件内容匹配（而非前一条应用后的内容），so that 我能基于一份快照同时生成多处修改、不必预测中间态。
4. As an LLM agent, I want 单处修改也用只含一条的 `edits[]` 表达，so that 单编辑与多编辑是同一个心智模型与工具。
5. As a Cafecli 用户, I want 两条 edit 命中区间重叠时收到明确报错（指出第几条与第几条重叠），so that 我知道该把相邻改动合并成一条。
6. As an LLM agent, I want 相邻但不相交的两条 edit（前一条结尾正好接下一条开头）被允许，so that 我能精确切分紧挨的改动而不被误判重叠。
7. As an LLM agent, I want 用 `replace_all=true` 在一条 edit 内替换某片段的全部非重叠匹配，so that 批量改名/改常量不必逐处补上下文。
8. As an LLM agent, I want `replace_all=false`（默认）时 `old_string` 必须唯一、否则报错，so that 我不会盲改到错误位置。
9. As a Cafecli 用户, I want `replace_all` 展开出的多个匹配也参与跨 edit 重叠检测，so that 批量替换不会和别的 edit 撞车还静默生效。
10. As an LLM agent, I want 显式空 `new_string` 表示删除匹配内容，so that 删除是一个明确合法的操作。
11. As an LLM agent, I want 漏传 `new_string`（key 缺失）时报错而不是被当成删除，so that 我的漏参不会静默删掉代码。
12. As an LLM agent, I want `old_string` 为空时被指向 `write_file`，so that 我用对工具去新建/整文件重写。
13. As an LLM agent, I want 一条 edit 的 old==new、或整体应用后内容无变化时报错，so that 我能察觉这次调用其实什么都没改。
14. As an LLM agent, I want `edits[]` 为空时报错，so that 我知道至少要给一条替换。
15. As a Cafecli 用户, I want 我的 CRLF 文件被编辑后仍保持 CRLF 行尾，so that diff 不会因行尾翻动炸成整文件改动。
16. As an LLM agent, I want 我用 LF 写的 `old_string` 能匹配 CRLF 文件，so that 我不必关心目标文件的行尾风格。
17. As a Cafecli 用户, I want 带 BOM 的文件被编辑后仍保留 BOM，so that 文件编码标记不被破坏。
18. As an LLM agent, I want 精确匹配失败时对**单独一行**做容忍匹配（智能引号/破折号/尾随空白/全角空格/NFKC），so that 引号或尾随空白的细微差异不至于让整条编辑失败。
19. As a Cafecli 用户, I want 模糊匹配只改命中的那一行、文件其余字节保持不变，so that 不会因一处模糊命中就把整文件的引号/空白静默归一。
20. As an LLM agent, I want 当 `old_string` 跨多行而需走模糊匹配时直接报错，so that 多行容忍匹配的风险被挡在门外、我改用精确匹配。
21. As an LLM agent, I want 结果里显式标注哪条 edit 用了模糊匹配、命中第几行，so that 我能复核这次 lossy 命中是否落在我想要的位置。
22. As an LLM agent, I want 模糊匹配只约束 `old_string` 命中一行，而 `new_string` 仍可为任意多行，so that 我能把一行替换成多行（插入）。
23. As an LLM agent, I want `edit_file` 能接收 `edits` 数组参数，so that 结构化的多处编辑能真正传进工具，而不是被拍平成空串。
24. As an LLM agent, I want 我把 `edits` 误发成 JSON 字符串（某些模型会这样）时仍被正确解析，so that 我的调用不因序列化习惯差异而失败。
25. As an LLM agent, I want 我沿用旧的顶层 `old_string`/`new_string` 写法时被自动折叠成一条 `edits[]`，so that 习惯性写法不会直接报错。
26. As a Cafecli 用户, I want 其它工具（`read_file`/`grep_code`/`web_*` 等）在入参通道改造后行为完全不变，so that 这次重构不引入回归。
27. As a 后续贡献者, I want 新增需要结构化参数的内置工具时直接用 `JsonNode`/DTO，老的标量工具用适配器过渡，so that 我不再被有损的 Map 拍平限制。
28. As a Cafecli 用户, I want 每次 `edit_file` 在终端显示 git 风格的合并行内 diff，so that 我实时看到这一批改了什么。
29. As an LLM agent, I want 结果里带紧凑 diff 回显（超长截断），so that 我能廉价自检改动是否符合意图、不烧太多 token。
30. As a Cafecli 用户, I want 同文件连续 `edit_file` 的 diff 事件按提交顺序、各发一次，so that 我看到的变更历史与磁盘演进一致。
31. As a Cafecli 用户, I want 编辑后的 LSP 诊断在下一轮注入对话，so that Agent 能自纠正它刚引入的编译错误。
32. As a Cafecli 用户, I want 同一工具批次里两个 `edit_file` 打同一文件时被**文件互斥锁**串行化，so that 不会丢更新。
33. As a Cafecli 用户, I want 不同文件的 `edit_file` 仍并行，so that 并发安全不牺牲多文件批改速度。
34. As a Cafecli 用户, I want 工具批次超时或 ESC 取消时等锁的编辑放弃执行，so that 取消即时彻底、无幽灵写入。
35. As a Cafecli 用户（HITL 开启）, I want `edit_file` 与 `write_file` 一样先经我审批，so that 编辑事务不成为绕过人工审批的旁路。
36. As a 安全审计者, I want `edit_file` 的路径越界与超大小上限记为 deny、普通业务失败（0 匹配等）记为 allow，so that 写操作的安全边界可解释、与 `write_file` 一致。
37. As an LLM agent, I want 编辑后内容超 5MB 上限时被策略拒绝，so that 误生成的超大内容不会灌满磁盘。
38. As an LLM agent, I want `old_string` 与文件不一致（0 匹配）时收到「请确认与文件完全一致（含缩进/空白/换行）」的报错，so that 我知道是匹配问题、据此补上下文重试。
39. As an LLM agent, I want 工具描述用 pi 式 guideline 告诉我「同一文件多处修改用一次调用的多条 `edits[]`，不要分多次 `edit_file` 调用」，so that 我主动规避工具批次互不可见的坑。
40. As an LLM agent, I want `write_file` 描述与系统提示引导我「改已有文件优先 `edit_file`」，so that 我默认选更省 token、更安全的编辑方式。
41. As a SWE-bench 评测工程师, I want 一个系统属性在 baseline 臂完全移除 `edit_file`（含 schema），so that A/B resolve rate Δ 可复现、可归因。
42. As a SWE-bench 评测工程师, I want 从 trace 区分 `edit_file` 与 `write_file` 占比、编辑事务的 edits 条数分布、截断/超限次数，so that 我能解释为什么涨/没涨。
43. As a 后续贡献者, I want 编辑事务术语在 `CONTEXT.md`、两处偏离在 ADR 0002、入参通道在 ADR 0003 有记录，so that 我不会把 `replace_all` 删掉、不会把模糊匹配改成 pi 式整文件归一、不会把入参改回 Map 拍平。

## Implementation Decisions

**入参通道（ADR 0003）**
- 内置工具执行器契约从「接收拍平后的 `Map<String,String>`」改为「接收解析后的 `JsonNode`」；工具批次分发不再对内置工具做 `asText()` 拍平。
- 遗留只吃标量参数的工具用一个 `fromMap` 适配器包一层，内部仍做同款拍平，行为字节级不变——有损拍平从「对所有工具的默认」降级为「显式按需适配」。
- `edit_file` 直接消费 `JsonNode` 并反序列化成 typed 请求对象。MCP 工具不受影响（本就走独立的原始 JSON invoker 通道）。
- `ToolExecutor` 在仓库内零外部实现，签名变更影响面收敛在工具注册表内。

**工具面与 Schema**
- `edit_file` 入参收敛为 `{path, edits[]}`，顶层单编辑参数移除。每条 edit 为 `{old_string, new_string, replace_all?}`。schema 形状（编码自设计决策）：
  ```
  {
    path: string (required),
    edits: array (required, 非空) of {
      old_string: string (required),
      new_string: string (required),   // 显式空串 = 删除
      replace_all?: boolean (default false)
    }
  }
  ```
- 入参容忍（反序列化前的 prepare 步骤）：`edits` 为 JSON 字符串 → 解析为数组；顶层 `old_string`/`new_string` → 折叠成一条 edit 追加进 `edits`。

**编辑事务语义（ADR 0002，pi 蓝本）**
- 全程在「BOM 剥离 + LF 归一」内容空间匹配与重建；写盘时还原原始行尾与 BOM（CRLF/BOM 无损 round-trip）。
- 每条 edit 先 exact 匹配（非重叠计数）；exact 0 命中才回退 whole-line fuzzy。
- whole-line fuzzy：`old_string` 归一后须等于**某一整行**（NFKC + 尾随空白 strip + 智能引号/破折号/特殊空格→ASCII，仅用于整行与 `old_string` 的相等比较，绝不整文件重写）；`old_string` 跨多行 → 报错；命中整行替换、其余字节不变。`old_string` 限一行，`new_string` 可多行。
- `replace_all=false` 要求该条唯一命中；`true` 展开全部命中。所有命中收集成区间，跨 edit 排序后做**严格**重叠检测（相邻允许、相交报错）。
- 单次重建：全部命中对原始（LF）内容一次性应用；整体无变化 → 报错。失败 fail-fast，报错点名第几条 edit（1-based）。
- 资源/安全：写盘内容超 5MB 上限 → 策略拒绝（与 `write_file` 一致）；路径越界 → 策略拒绝。其余业务失败（0 匹配/歧义/重叠/缺参）返回错误字符串、文件不变、审计记 allow。
- 结果文本：成功返回「文件已编辑 + 共 N 条编辑/替换 M 处 + 紧凑 diff（超长截断）+ 每条 fuzzy 命中标注（命中第几行）」。

**集成（全部复用现有设施）**
- 文件互斥锁（ADR 0001）罩整个读改写执行体：同文件串行、异文件并行；等锁可中断 + 获锁后复检取消。
- diff 观察者在锁内触发，收 **LF 空间**的 before/after（一次合并 diff，干净）；写盘内容带原始行尾/BOM。LSP 钩子在放锁后、仅成功时触发。
- HITL 审批、审计名单、A/B 移除开关、行内 diff 渲染：`edit_file` 已纳入，沿用，与 `write_file` 对齐。
- 引导：工具描述改 pi 式 guideline；base 模式系统提示「分轮顺序」→「一次调用多条 `edits[]`」。AGENTS.md 核心工具数仍 12。

**文档**
- 术语「编辑事务」收进 `CONTEXT.md`；两处偏离记 ADR 0002；入参通道记 ADR 0003。

## Testing Decisions

- **好测试只断言外部行为**：磁盘终态（含行尾/BOM 字节）、工具返回字符串、观察者回调序列与内容、工具 schema/描述、审计 allow/deny——不断言匹配/锁/归一的内部状态。
- **全部走现有四条缝，不新增缝**（用户已确认）：
  1. `executeTool(name, argsJson)`（最高缝）——单调用编辑事务全矩阵：多条 disjoint 全落盘、0 匹配、区间重叠、歧义、`replace_all` 的 exact 与 fuzzy 两路、空串删除、空 `edits`、缺 `new_string`、CRLF/BOM 保留、whole-line fuzzy 命中且只改该行其余字节不变、多行 `old_string` 走 fuzzy→报错、fuzzy 标注出现在结果、prepareArguments 两种容忍（stringified `edits`、顶层折叠）、超限 deny、越界 deny、文件不存在→指向 `write_file`。
  2. `executeTools(batch)`——文件互斥锁并发契约：同文件两 edit 串行（提交链：第二次的 before == 第一次的 after）、异文件并行。
  3. `setWriteFileObserver`——一次 `[before, after]`（LF 空间），同文件连续编辑按提交顺序。
  4. `getToolDefinitions()` + `ApprovalPolicy` 静态 + `HitlToolRegistry` + `DocumentationSyncTest`——edits[] schema 形状与必填、`EDIT_FILE_DESC` 引导文案、`write_file` 仍引导 `edit_file`、HITL/审计元数据、AGENTS.md 工具数 12、A/B 开关下工具消失。
- CRLF/BOM/fuzzy 归一助手**不单独测**，经 `executeTool` 的磁盘终态间接覆盖（断言「CRLF 文件改完仍 CRLF」，不断言 `detectLineEnding(...)`）。
- `fromMap` 通道改造经现有遗留工具的 `executeTool` 测试做**回归**（read_file/grep/glob/web 行为不变），不新增缝。
- 先例：既有 `ToolRegistryTest`（`@TempDir` + `setProjectPath` + `executeTool` 断言返回串与磁盘内容）、`HitlToolRegistryTest`、`ApprovalPolicyTest`、`DocumentationSyncTest`。
- 并发/重叠/fuzzy 用例多跑几次确认非侥幸（无锁/错误实现时断言为 flaky-fail）。

## Out of Scope

- sub-span fuzzy（本轮只做 whole-line；模型发来仅靠行内片段模糊才能命中的 `old_string` 会被要求改 exact 或整行）。
- 用 `edit_file` 新建文件（仍走 `write_file`）。
- read-before-edit / readState 守卫。
- 跨进程互斥（OS 文件锁）。
- 逐个把其余标量工具改成各自 typed DTO（本轮只统一执行器签名 + `fromMap` 适配；per-tool DTO 机会性推进）。
- `write_file` 执行语义变更（仅描述文案 + 复用公共落盘尾部）。
- TUI `ToolCallRenderer` 给 `edit_file` 专属「编辑 N 处」标签/path 提取（纯展示增强，另排）。
- SWE-bench trace normalize 把 `edit_file` 归类为 edit（eval 侧，另行裁决）。

## Further Notes

- 蓝本：pi agent 的 `edit` 工具 + `applyEditsToNormalizedContent`（`earendil-works/pi`，`packages/coding-agent/src/core/tools/edit.ts` / `edit-diff.ts` / `file-mutation-queue.ts`）。按 Cafecli 的 Java 线程模型与 HITL/审计/快照设施做适配，并有两处刻意偏离（ADR 0002）：保留 `replace_all`（pi 无）、fuzzy 限定 whole-line + region-local（pi 是整文件 lossy 归一）。
- 决策记录：ADR 0001（文件互斥锁）、ADR 0002（edit_file 偏离 pi）、ADR 0003（工具入参 JsonNode 通道）。术语见 `CONTEXT.md`（编辑事务、工具批次、文件互斥锁）。
- 承接 `.scratch/edit-file-tool/PRD.md`（单编辑 + 锁，已落地）。本 PRD 是其 fast-follow；因 base（commit 0d43ee1）未发布，可破坏单编辑 schema、无外部兼容负担，仍在 `feat/edit-file-tool` 上提交。
- 固有限制（接受并已引导）：工具批次内兄弟调用互不可见——编辑事务把同文件多处修改收进一次调用即规避；锁只保证串行落盘不丢更新，跨调用相交编辑仍由唯一匹配/重叠检测明确报错、模型重试。
- 实现计划：本地 agent 计划文件（已对齐本 PRD，实现就绪；未入库）。
- 北极星指标：SWE-bench A/B 的 resolve rate Δ + `edit_file`/`write_file` 使用占比 + 编辑事务 edits 条数分布 + max_output_tokens 截断/超限次数。
