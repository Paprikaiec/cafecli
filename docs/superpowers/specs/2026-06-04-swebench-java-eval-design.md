# SWE-bench-java-verified 评测方案设计（PaiCLI vs Claude Code 裸跑对比）

- 日期：2026-06-04
- 状态：草案，待用户审阅
- 目标读者：PaiCLI 维护者 / 评测执行者

## 1. 背景与目标

在 **SWE-bench-java-verified** 上，公平对比两个 coding agent harness —— **PaiCLI** 与 **Claude Code** —— 在同一底层模型（`deepseek-v4-pro`）下解决真实 Java 任务的成功率（`resolved%`），先抽 25 条跑通，得到第一组可对标的对比数字，并沉淀为可复跑流水线。

**核心是「裸跑」**：两个框架都不外挂任何 skill / 工作流增强，测的是 **harness 框架本身**的能力（工具设计、上下文管理、ReAct 循环质量、prompt 工程）。

成功率定义沿用 SWE-bench：实例被判 `resolved`（打上 agent 补丁 + 隐藏 `test_patch` 后，`FAIL_TO_PASS` 全转绿且 `PASS_TO_PASS` 不回归）。

**附加产出**：每臂每条实例都落「完整对话 + tool use」trace（**raw 全量** 供深挖个案 + **规范化摘要** 供跨臂统计），用于解释成功率差异 —— 平均轮数、工具调用分布、是否自我验证（跑构建/测试）、读改文件数、token/耗时。

**不在本期目标内**：skill / superpowers 增量对比（理由见 §2.1，若做须对等，列为可选阶段三）、多模型对比、扩到全 91 条、容器安全沙箱加固。

## 2. 关键决策（已与用户对齐）

| 维度 | 决策 | 理由 |
|------|------|------|
| 评测集 | SWE-bench-java-verified（91 条，`java_verified` split），抽 25 条 | 官方 Java 评测集，带 Docker 评分 harness 与 leaderboard |
| 对比对象 | **A = PaiCLI**、**B = Claude Code**，两臂裸跑 | 直接回答「两个 harness 谁强」|
| 模型 | 两臂统一 `deepseek-v4-pro` | 控制变量，唯一差异是 harness 框架 |
| Claude Code 接 DeepSeek | DeepSeek 官方 Anthropic 兼容端点，无需第三方代理 | `ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic` |
| 抽样 | 按仓库分层随机 + 固定 seed（42，25 条）| 小样本保留 6 仓库代表性 |
| 解题环境 | 每条实例的官方 Docker 镜像内跑 agent | 镜像自带「仓库@base_commit + JDK + 构建工具」，可自我验证 |
| 评分 | 官方 multi-swe-bench-env harness | 唯一可信、可对标 leaderboard 的判定 |
| 行为日志 | 全量 raw + 规范化摘要 | 解释成功率差异，PaiCLI raw 对齐 Claude Code stream-json 语义以简化归一 |
| 正式批量运行位置 | x86_64 Linux 云机 | 官方镜像基本 x86_64；本机 arm64 走 QEMU 慢且不稳。本地 Mac 仅开发 + smoke |

### 2.1 为什么裸跑（方法论）

1. **控制变量**：A vs B 裸跑，唯一变量是框架实现。加 skill 会混入「skill 质量 / 触发逻辑 / 与框架兼容性」等变量，无法干净归因。
2. **SWE-bench 本身就在测 agentic 能力**：强 harness 裸跑时就应内建高质量探索与自我验证；若需外挂 skill 才能做好，恰恰暴露框架短板，加 skill 反而抹平差距。
3. **skill 是增量，应分层且对等**：先建裸跑 baseline，再单独测 skill 的 Δ；若测 skill 须 PaiCLI SkillRegistry vs Claude Code superpowers 对等。→ 降级为可选阶段三。

## 3. 整体架构

解题与评分严格分离。同一抽样集，每条实例分别用两臂各跑一次，各自产出补丁 + trace，各自评分，最后并排对比。

```
                       eval/swebench-java/run_eval.py（编排器）
                                     │
  数据集(HF) ──分层抽样(seed)──▶ instances.txt（冻结清单，可复跑）
                                     │  对每条实例 × 每个臂
                                     ▼
        docker run --platform linux/amd64 <实例镜像>
          内含：仓库@base_commit + JDK + Maven/Gradle
          注入：DeepSeek Key / issue.md / 对应 agent 运行时
              ├─ 臂 A：java -jar /opt/paicli.jar run --file issue.md
              │          --trace-file trace.jsonl（HITL 自动放行）
              └─ 臂 B：claude -p < issue.md --output-format stream-json --verbose
                         --dangerously-skip-permissions | tee trace.jsonl
                                     │
                  git diff ──▶ model_patch        trace.jsonl ──▶ traces/<arm>/
                                     │  按臂汇总
                                     ▼
              predictions/paicli.jsonl     predictions/claude.jsonl
                                     │  各自评分                   │  归一
                                     ▼                              ▼
        run_evaluation（官方 harness，跑两次）          traces/normalized/*.jsonl
              reports/paicli.json / claude.json                     │
                                     └──────────┬───────────────────┘
                                                ▼  summarize.py 合并
                                    reports/compare.md
                          （A vs B 成功率 + 行为指标并排对比）
```

## 4. 组件设计

### 4.1 臂 A —— PaiCLI headless 入口（需改源码）

**问题**：容器内需要「一条命令跑完一条 issue 然后退出」并产出可分析 trace。当前 PaiCLI 只有交互模式和 `serve`（异步 HTTP，编排繁琐）。

**方案（已定）**：新增同步子命令

```
java -jar paicli.jar run --file <issue.md> --trace-file <trace.jsonl> \
     [--max-turns N] [--timeout-seconds S]
```

- 读 issue 文件作为单轮输入；复用交互模式同一套装配（`ToolRegistry` + `LlmClientFactory` 选中 DeepSeek + ReAct `Agent` 循环）
- **HITL 自动放行**：headless 下所有工具自动批准（等价 Claude Code 的 `--dangerously-skip-permissions`），否则卡审批且对 B 不公平
- 模型固定 `deepseek-v4-pro`（容器内注入 `DEEPSEEK_API_KEY` + `DEEPSEEK_MODEL=deepseek-v4-pro`）
- **`--trace-file` 落 raw 全量 NDJSON**：复用现有 `Agent.conversationHistory` + `LlmTraceLogger`（reasoning）+ `AuditLog`（工具执行）。每行一个事件，**语义对齐 Claude Code stream-json**：`assistant`（含 tool_use：name+args）、`tool_result`（配对 + 完整出参）、`usage`（in/out/cache token）、`result`。注意：现有 `AuditLog` 只记危险工具，trace 要记**全部**工具调用。
- 最终回答写 stdout、过程日志写 stderr；正常 `exit 0`，超时/异常非零；最大轮数 + 墙钟超时双保护；PathGuard 仍限制改动在仓库根内

**联动（AGENTS.md 硬规则 #2）**：`Main.java` + `CliCommandParser.java` + 测试 + `README.md` + `AGENTS.md`。

（备选记录：不改源码用 `paicli serve &` + curl 驱动，更绕、异步完成与 trace 落盘判定更脆，已否决。）

### 4.2 臂 B —— Claude Code（DeepSeek 接入 + 原生 trace）

镜像内一次性安装与配置：

```bash
npm i -g @anthropic-ai/claude-code             # 镜像无 Node 则 solver 里装
export ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic
export ANTHROPIC_AUTH_TOKEN=<DeepSeek Key>
export ANTHROPIC_MODEL=deepseek-v4-pro
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
claude -p "$(cat issue.md)" \
  --output-format stream-json --verbose \
  --dangerously-skip-permissions [--max-turns N] | tee trace.jsonl
```

- `-p` headless 非交互；`--dangerously-skip-permissions` 工具全自动放行（与 A 对等）
- `--output-format stream-json --verbose` **原生**逐事件 NDJSON：`assistant` 含 `tool_use`（name+input）+ `usage`，`user` 含 `tool_result`（`tool_use_id` 配对），`result` 给 cost —— `tee` 直接落盘，**零额外开发**
- 工作目录 = 仓库根；**裸跑**：干净 `~/.claude`，不加载任何 plugin / superpowers / 自定义 skill
- 跑完同样 `git diff` 取补丁

### 4.3 Solver（容器内 `solve_one.sh`，按臂参数化）

入参含 `instance_id`、镜像名、仓库路径、issue 文件、`arm=paicli|claude`：

1. 记录前置 commit：`git -C <repo> rev-parse HEAD`
2. 按 `arm` 跑对应 agent（带超时），trace 落 `trace.jsonl`
3. 取补丁：`git -C <repo> add -A && git -C <repo> diff --cached <base_commit>`（评分会强制还原测试文件再打 `test_patch`，agent 误改测试会被覆盖；模板已要求不改测试）
4. 补丁 + trace 由编排器捕获；非零退出 / 空补丁如实上报

### 4.4 issue → prompt 模板

外层指令对两臂保持**语义一致**（措辞已定），仅按各框架习惯最小适配：

```
你当前位于一个 Java 项目仓库的根目录。下面是一个待解决的 GitHub issue。
请：定位相关代码 → 修改源码修复问题 → 用项目的构建命令确认能编译通过。
约束：
- 只修改必要的源码文件；不要修改任何测试文件；非必要不要改构建配置。
- 修复后请简述你改动了哪些文件、为什么。

<issue>
{problem_statement}
</issue>
```

注：`test_patch` 与 `FAIL_TO_PASS` 隐藏，两臂都看不到，只能依赖仓库现有测试与自身复现验证。

### 4.5 Orchestrator（`run_eval.py`）

1. 载入数据集（HF `Daoguang/Multi-SWE-bench` 的 `java_verified` split）
2. 按 `repo` 分层随机抽样（`seed=42`，25 条）→ 写 `instances.txt` 冻结
3. 对每条实例 × 每个臂：拼 issue.md → `docker run` 执行 solver → 收集 `model_patch` + `trace.jsonl`
4. 容错：单条超时 / 异常 / 空补丁 → 记空 patch（计未解决），**不中断整批**
5. 按臂汇总 `predictions/paicli.jsonl`、`predictions/claude.jsonl`，每行：
   ```json
   {"instance_id":"...","model_name_or_path":"paicli-dsv4pro","model_patch":"<diff>"}
   ```
   （字段名以 harness 实际要求为准，阶段 1 用 gold 跑通时核对）

### 4.6 Trace normalizer（`normalize_trace.py`）

把两臂异构 raw trace 归一成统一摘要，每条实例每臂一行 `traces/normalized/<arm>.jsonl`：

```json
{"instance_id":"...","arm":"paicli","turns":7,"wall_ms":182000,
 "tokens":{"in":..,"out":..,"cache":..},
 "tool_calls_total":21,
 "tool_breakdown":{"read":9,"edit":4,"exec":5,"search":3},
 "ran_build":true,"ran_tests":true,"files_read":9,"files_edited":3,"exit_status":"ok"}
```

- **工具按语义类别归一**：PaiCLI（`read_file`/`write_file`/`execute_command`/`grep_code`…）与 Claude Code（`Read`/`Write`/`Bash`/`Grep`…）映射到统一类别（read/edit/exec/search/…），跨臂才可比
- `ran_build`/`ran_tests` 从 exec 类工具的命令文本识别（`mvn`/`gradle`/`test`）
- `resolved` 字段在 §4.7 合并评分结果时回填

### 4.7 Scorer + Reporter

- 评分（两臂各跑一次）：
  ```
  python -m swebench.harness.run_evaluation \
    --dataset_name Daoguang/Multi-SWE-bench \
    --predictions_path eval/swebench-java/predictions/<arm>.jsonl \
    --run_id paicli-dsv4pro-r1 | claude-dsv4pro-r1 --max_workers <N>
  ```
- `summarize.py` 合并「评分 report + normalized trace」→ `reports/compare.md`：
  - **成功率**：两臂总成功率并排、按仓库成功率对比、逐条 A/B 对照与重叠分析（都过 / 都挂 / 仅 A 过 / 仅 B 过）
  - **行为指标**（来自 normalized）：每臂平均轮数、工具调用分布、`ran_tests` 比例、平均读/改文件数、平均 token/耗时/成本
  - **关联分析**：如「跑了测试的实例 resolved 率 vs 没跑的」「仅 A 过的实例，A 的行为有何共性」
  - 失败分类：解题失败（agent 异常/超时/空补丁）vs 评分失败（patch 应用失败 vs 测试不过）
  - 每条附 raw trace 链接，供肉眼深挖

## 5. 公平对比的控制变量（关键）

| 项 | 两臂统一做法 |
|----|--------------|
| 抽样集 | 同一份 `instances.txt` |
| 模型 | `deepseek-v4-pro` |
| 任务输入 | 同一 issue 文本 + 语义一致指令模板（§4.4）|
| 工具放行 | 都全自动放行（A: HITL 自动批准；B: `--dangerously-skip-permissions`）|
| 预算 | 同一墙钟超时（默认 30 分钟）+ 最大轮数；轮数语义两框架不同，至少对齐墙钟超时 |
| 评分 | 同一官方 harness |
| 增强 | 都裸跑，不挂任何 skill / plugin |

## 6. 目录结构（独立在 `eval/`，不污染主代码）

```
eval/swebench-java/
├── README.md            # 如何复跑
├── run_eval.py          # 编排：抽样 + 双臂解题 + 汇总
├── solve_one.sh         # 容器内单实例解题（arm=paicli|claude）
├── normalize_trace.py   # 两臂 raw trace → 统一摘要
├── summarize.py         # 合并评分 + 行为指标，出对比
├── instances.txt        # 冻结的 25 个 instance_id（可复跑）
├── prompt_template.md
├── predictions/         # paicli.jsonl / claude.jsonl
├── traces/
│   ├── paicli/<instance>.jsonl    # raw 全量
│   ├── claude/<instance>.jsonl    # raw 全量（stream-json）
│   └── normalized/<arm>.jsonl     # 规范化摘要
└── reports/             # paicli.json / claude.json / compare.md
```

## 7. 分阶段实施与验收

| 阶段 | 内容 | 验收标准 |
|------|------|----------|
| 0 | 两臂各自 headless 跑通：A 加 `paicli run` 子命令（含 `--trace-file`）+ 单测；B 镜像内装 Claude Code + 接 DeepSeek。公开小仓库本地 smoke | 两臂都能跑完一条任务、产出非空 git diff + 非空 raw trace、正常退出 |
| 1 | 单条实例两臂闭环 + normalizer。**先用 `--predictions_path gold` 跑官方 harness** 确认抽到的实例全部 resolved（验证环境正确），再换两臂预测各跑通 1 条 | gold 跑出 100% resolved；A、B 各端到端跑通 1 条；normalized 摘要字段正确 |
| 2 | 云机上 25 条 × 2 臂全量跑 + 出对比报告 | 产出 `reports/compare.md`，含两臂成功率 + 行为指标对比 |
| 3（可选）| 对等 skill 增量（PaiCLI SkillRegistry vs Claude Code superpowers）、扩全 91 条、多模型 | 后续另议 |

## 8. 横切关注点

- **可复跑性**：`instances.txt` + `seed` + `run_id` 固定；同清单可换模型 / 换日期重跑对比。
- **安全**：DeepSeek Key 只通过 `docker run -e` 注入（A 用 `DEEPSEEK_API_KEY`，B 用 `ANTHROPIC_AUTH_TOKEN`，值相同），不写进镜像、不入 git；**raw trace 落盘前脱敏**（Key/Bearer/base64 等），沿用 `AuditLog` 的截断思路控制超长字段；遵守 AGENTS.md #6。
- **存储**：raw trace 含完整代码文本，体积可观；按 `instance` 分文件，必要时 gzip 归档。
- **资源与超时**：每条 agent 墙钟超时默认 30 分钟 + 最大轮数；harness `--max_workers = min(0.75*CPU, 24)`；云机 ≥120GB 磁盘 / ≥16GB 内存 / ≥8 核。
- **网络**：容器默认 bridge，保证两臂都能访问 DeepSeek API；B 设 `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1` 避免连 Anthropic。

## 9. 待确认 / 风险项（均在阶段 0/1 执行时验证，无需事前决策）

1. **PaiCLI raw trace schema**：最终字段与「对齐 Claude Code stream-json 语义」的细节，阶段 0 定稿。
2. **Claude Code headless 稳定性**：`deepseek-v4-pro` 走 Anthropic 兼容端点 + `--dangerously-skip-permissions` + `stream-json` 在镜像内的实际行为、是否需额外 flag —— 阶段 0 验证。
3. **镜像来源**：multi-swe-bench-env 的 Java 镜像是 DockerHub 拉还是本地 build —— 阶段 1 确认。
4. **数据集 split 一致性**：确认 HF `java_verified` 行数与论文 91 条一致。
5. **predictions 字段名**：以 harness 实际要求为准，阶段 1 gold 跑通时核对。

## 10. 参考

- 论文：SWE-bench-java，arXiv [2408.14354](https://arxiv.org/abs/2408.14354)
- Harness：[multi-swe-bench/multi-swe-bench-env](https://github.com/multi-swe-bench/multi-swe-bench-env)
- 数据集：HF [Daoguang/Multi-SWE-bench](https://huggingface.co/datasets/Daoguang/Multi-SWE-bench)
- 官方 Docker 指南：[swebench.com/guides/docker_setup](https://www.swebench.com/SWE-bench/guides/docker_setup/)
- DeepSeek × Claude Code 集成：[api-docs.deepseek.com/.../claude_code](https://api-docs.deepseek.com/quick_start/agent_integrations/claude_code)
- Claude Code headless / stream-json：[code.claude.com/docs/en/headless](https://code.claude.com/docs/en/headless)
