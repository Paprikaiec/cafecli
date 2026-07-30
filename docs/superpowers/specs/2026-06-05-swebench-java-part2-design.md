# SWE-bench-java 评测 Part 2 设计（编排流水线）

- 日期：2026-06-05
- 状态：草案，待用户审阅
- 关系：主方案 `docs/superpowers/specs/2026-06-04-swebench-java-eval-design.md` 的 **Part 2 实施**。Part 1（`cafecli run` headless + trace）已实现并合并 `main`。

## 1. 背景与本次范围

Part 2 = 把评测串起来的编排流水线。本次按「本地能扎实做的先做、依赖云机的写骨架」切两层：

- **L1 纯逻辑**（无需 Docker/云机/key）：分层抽样、predictions 汇总、trace 归一化、对比报告、数据集加载——**本地 TDD 做扎实**。
- **L2 外部集成**（需 x86 云机 + Docker + DeepSeek key + 镜像）：容器内解题、docker 编排、harness 评分、Claude Code 臂镜像——**写骨架**（命令确切、可读），本地不执行，留云机验证。

**本次验收**：L1 五个模块 + `unittest` 本地全绿；L2 脚本静态写好。云机端到端执行是后续（按主 spec §7 阶段 1→2）。

## 2. 技术栈

- **Python 3 标准库**：`unittest`（测试）、`urllib`/`json`（数据集）、`random`（抽样）、`collections`。**零第三方依赖**。
- 云机脚本：`bash` + `docker` + `jq` + multi-swe-bench-env harness。

理由：本机 arm Mac 无 `pytest`/`datasets`/`pandas`/`pyarrow`；标准库足以覆盖 L1，避免改动用户 Python 环境。

## 3. 已确定的外部事实（研究 + 实测确认）

- **数据集加载**：HF datasets-server REST 可用且 Bash 可联网。
  - `https://datasets-server.huggingface.co/rows?dataset=Daoguang/Multi-SWE-bench&config=default&split=java_verified&offset=<o>&length=<l>`
  - `config=default`，`split=java_verified`，**`num_rows_total=91`**；行字段含 `instance_id`/`repo`/`problem_statement`/`base_commit`/`patch`/`test_patch`/`FAIL_TO_PASS`/`PASS_TO_PASS` 等。
- **predictions 格式**：JSONL，每行 `{"instance_id","model_name_or_path","model_patch"}`（`instance_id` 形如 `repo_owner__repo_name-issue_number`）。
- **harness 命令**：`python -m swebench.harness.run_evaluation --dataset_name Daoguang/Multi-SWE-bench --predictions_path <gold|path.jsonl> --max_workers <N> --run_id <id>`，可加 `--instance_ids`、`--cache_level none/base/env/instance`、`--clean True`。
- **6 仓库**：`google/gson`、`FasterXML/jackson-databind`、`jackson-core`、`jackson-dataformat-xml`、`apache/dubbo`、`GoogleContainerTools/jib`。
- **Claude Code 接 DeepSeek**：`ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic`、`ANTHROPIC_MODEL=deepseek-v4-pro`、`claude -p "$(cat issue.md)" --output-format stream-json --verbose --dangerously-skip-permissions`。

## 4. 目录结构

```
eval/swebench-java/
├── README.md            # 如何本地跑 L1 测试 + 云机跑 L2
├── lib/
│   ├── __init__.py
│   ├── dataset.py       # L1 数据集：解析(纯) + 获取(REST)
│   ├── sampling.py      # L1 分层随机抽样（纯函数）
│   ├── predictions.py   # L1 records → predictions.jsonl
│   ├── normalize.py     # L1 两臂 raw trace → 统一行为摘要
│   └── report.py        # L1 harness report + normalized → compare.md
├── tests/
│   ├── test_dataset.py      # 解析 mock 离线测 + 1 个 integration 真连测
│   ├── test_sampling.py
│   ├── test_predictions.py
│   ├── test_normalize.py
│   └── test_report.py
├── run_eval.py          # L2 骨架：抽样 → docker solve → 汇总
├── solve_one.sh         # L2 骨架：容器内单实例解题（arm=cafecli|claude）
├── score.sh             # L2 骨架：调 harness 评分（gold + 两臂）
├── prompt_template.md   # issue → agent 输入模板（主 spec §4.4）
├── predictions/         # cafecli.jsonl / claude.jsonl
├── traces/              # cafecli/<id>.jsonl, claude/<id>.jsonl, normalized/<arm>.jsonl
└── reports/             # cafecli.json / claude.json / compare.md
                         # instances.txt 由 run_eval 抽样阶段生成
```

## 5. L1 组件（本地 TDD）

### 5.1 `dataset.py` —— 解析与获取分离

- `parse_rows(payload: dict) -> list[dict]`：**纯函数**，把 datasets-server 的 `rows` 响应解析成 `[{instance_id, repo, problem_statement, base_commit}]`。
- `fetch_java_verified(page_size=100) -> list[dict]`：用 `urllib` 按 `num_rows_total` 分页拉全 91 行，逐页交给 `parse_rows`。
- **测试（按用户指定）**：
  - 核心 `parse_rows` 用**构造的 mock payload 离线测**（断言字段映射、空/缺字段处理）。
  - 另留**一个 integration 真连测试**：`@unittest.skipUnless(os.environ.get("RUN_INTEGRATION"), "integration")` 真连 datasets-server，断言 `num_rows_total==91` 且每行有 `instance_id`/`repo`。默认跳过，`RUN_INTEGRATION=1 python3 -m unittest` 才跑。

### 5.2 `sampling.py`

- `stratified_sample(instances: list[dict], k=25, seed=42) -> list[str]`：按 `repo` 分组 → 按各组占比分配名额（最大余数法凑足 k）→ 每组用 `random.Random(seed)` 确定性抽样 → 返回 `instance_id` 列表（稳定排序）。
- **测试**：分层比例符合预期、同 seed 同结果（确定性）、总数==k、k 超过总量时的退化、各 repo 至少覆盖。

### 5.3 `predictions.py`

- `to_jsonl(records: list[dict], model_name: str) -> str`：每条 `{instance_id, patch}` → 一行 `{"instance_id","model_name_or_path":model_name,"model_patch":patch}`。
- **测试**：字段名正确、空 patch 处理、JSON 转义（含换行/引号的 diff）、行数。

### 5.4 `normalize.py` —— 核心

- `normalize_paicli(lines: list[str]) -> dict`：解析 Cafecli `AgentTraceWriter` 的 NDJSON（**格式我们自己定义，可用真实输出测**）。
- `normalize_claude(lines: list[str]) -> dict`：解析 Claude Code stream-json（按研究的结构：`assistant`含`tool_use`+`usage`、`user`含`tool_result`、`result`含 cost）。
- 工具按**语义类别归一**：Cafecli（`read_file`/`write_file`/`execute_command`/`grep_code`…）与 Claude Code（`Read`/`Write`/`Bash`/`Grep`…）映射到统一类别 `read/edit/exec/search/other`。
- 输出统一摘要：`{instance_id, arm, turns, tool_calls_total, tool_breakdown, ran_build, ran_tests, files_read, files_edited, tokens:{in,out,cache}}`（`ran_build`/`ran_tests` 从 exec 类命令文本识别 `mvn`/`gradle`/`test`）。
- **测试**：`normalize_paicli` 喂真实 AgentTraceWriter 输出样本断言；`normalize_claude` 喂按文档构造的 stream-json 样本断言；工具类别映射表逐类断言。**注**：Claude 解析器的真实字段细节留云机用真实 stream-json 复核（spec §9 未知之一）。

### 5.5 `report.py`

- `build_compare(cafecli_report: dict, claude_report: dict, normalized: dict) -> str`：产出 `compare.md`。
- 内容：两臂总成功率、按仓库成功率、逐条 A/B 对照与**重叠分析**（都过/都挂/仅A过/仅B过）、行为指标（平均 turns/tool_breakdown/ran_tests 比例/files/tokens）、关联分析（如「跑了测试的 resolved 率 vs 没跑的」）。
- **测试**：喂样本 report+normalized → 断言 markdown 含总成功率、重叠分类计数、行为指标行。

## 6. L2 骨架（命令确切、本地不执行）

- **`solve_one.sh`**（入参 `instance_id`/镜像/仓库路径/issue/`arm`）：`docker run --platform linux/amd64` 注入 Cafecli jar（A）或不注入（B 用镜像内 claude）、`DEEPSEEK_API_KEY`、issue.md → 跑 `cafecli run --file ... --trace-file ...`（A）或 `claude -p ... --output-format stream-json ... | tee`（B）→ `git -C <repo> add -A && git diff --cached <base_commit>` 取补丁。
- **`run_eval.py`** 的 docker 编排：载入数据集 → `stratified_sample` → 写 `instances.txt` → 逐条 × 两臂调 `solve_one.sh` → 收 patch+trace → `to_jsonl` 汇总。docker 调用处标注「云机执行点」，本地 `--dry-run` 只走抽样+汇总逻辑（可被 L1 间接覆盖）。
- **`score.sh`**：`python -m swebench.harness.run_evaluation ...`（先 `--predictions_path gold` 单实例验环境，再两臂 jsonl）。
- **Claude Code 臂镜像片段**：`npm i -g @anthropic-ai/claude-code` + DeepSeek Anthropic 端点 env + `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`。

## 7. 可测性边界与验收

| 层 | 本次状态 | 验证方式 |
|----|----------|----------|
| L1（dataset 解析/sampling/predictions/normalize/report）| 完成 + TDD | `python3 -m unittest discover eval/swebench-java/tests` 全绿 |
| L1 dataset 真连 | 完成（默认跳过）| `RUN_INTEGRATION=1 python3 -m unittest ...` 实连 datasets-server |
| L2（solve_one.sh/run_eval docker/score.sh/CC 镜像）| 骨架完成 | 静态可读、命令确切；云机阶段执行 |

## 8. 分阶段

- **本次**：L1（含 dataset 解析 mock 测 + integration 真连测）+ L2 骨架。
- **后续（云机，本 spec 范围外）**：按主 spec §7 —— 阶段 1 `gold` 验证 harness + 单条两臂闭环；阶段 2 抽样 25 条 × 两臂全量 + `compare.md`。

## 9. 剩余未知（仅云机能实证，非本次 blocker）

1. multi-swe-bench-env 的 Java 镜像是 DockerHub 拉还是本地 build（研究倾向本地 build，需 `repos/` + 分层 build；影响云机磁盘/时间）。
2. Claude Code stream-json 的真实字段嵌套细节（`normalize_claude` 本地用构造样本，云机用真实 trace 复核）。

## 10. 参考

- 主方案 spec：`docs/superpowers/specs/2026-06-04-swebench-java-eval-design.md`
- Harness：[multi-swe-bench/multi-swe-bench-env](https://github.com/multi-swe-bench/multi-swe-bench-env)
- HF datasets-server：`https://datasets-server.huggingface.co/`（`Daoguang/Multi-SWE-bench`）
- Claude Code headless：[code.claude.com/docs/en/headless](https://code.claude.com/docs/en/headless)
