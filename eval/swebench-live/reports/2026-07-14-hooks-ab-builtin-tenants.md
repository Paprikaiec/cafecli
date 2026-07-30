# 内建 Hook 租户 A/B — 收尾门 + 读前写台账（configurable-hooks #08）

- 背景叙事链：[2026-06-08 A/B](2026-06-08-cafecli-vs-claude-ab.md) 归纳出 4 项工程化修复（① 编辑后跑测试再收手 / ② 长程任务调高步数上限 / ③ writefile 改局部编辑 / ④ 不误改隐藏测试）；[2026-06-26 Phase 0](2026-06-26-phase0-editfile-rebaseline.md) 用两个零代码旋钮（`edit_file` + 硬轮数 80）验证了 ②③ 的一部分。**本期把 ①（测试反馈闭环）与 ③ 的写安全，产品化成两个可配置的内建 Hook 租户**，在同一 band 上做 A/B，量化它们对解决率和过程指标的影响。
- 被测租户（ADR 0012，wave 1）：
  - **收尾门（Finish Gate，Stop）**：本回合有成功写入、且最后一次写入后没有运行任何验证命令时，阻断收尾并回灌「先自证再交付」，续跑；单回合阻断上限 2，硬轮数上限兜底。
  - **读前写台账（Read-before-Write Ledger，PreToolUse+PostToolUse）**：写已存在但没读过的文件 → BLOCK「先读」；读后文件已变 → BLOCK「陈旧，重读」；新建文件放行。
- 数据：**SWE-bench-Live / MultiLang（java）**，Phase 0 的 6 条 core-fail 实例（2026-03/04，抗污染）。
- 臂设计（**同一 jar，唯一变量 = 5 个 hook `-D` 开关**）：
  - **`ctrl`（对照）**：`cafecli-hooks.jar` + `edit_file` on + 硬轮数 80，hook 全关（= Phase 0 配置）。
  - **`hooks`（处理）**：同上 + `-Dcafecli.hook.{stop,finish_gate,pre_tool_use,post_tool_use,read_ledger}.enabled=true`。
  - **`noedit`（紧配置回归带，仅量化失败形状）**：同一 `cafecli-hooks.jar` + `-Dcafecli.edit_file.disabled=true` + 默认硬轮数 50、hook 全关 —— 复现 2026-06-08 的紧配置（用当前 jar 是为保证 `--trace-file` 输出，非 Jun-6 jar；对失败率的影响见 §3 诚实校准），量化「收尾不测试」这一失败形状在它出现的地方有多普遍。
- 统一 **deepseek-v4-pro**、对称 prompt、官方 harness 评分。单次运行（n=1/实例·臂），结论为**方向性 + 结构性**。

## 1. 主结果：Phase 0 band 上 A/B（ctrl vs hooks）

| 实例 | 任务 | ctrl | hooks | Δ解决率 | 收尾门阻断 | 台账阻断 |
|---|---|---|---|---|---|---|
| floci-164 | S3 multipart（3 文件） | ✅ 1/0 | ✅ 1/0 | 0 | 0 | 0 |
| floci-248 | ACM import/export | ❌ 0/0 | ❌ 0/0 | 0 | 0 | 0 |
| floci-88 | S3 virtual-host filter | ❌ 0/0 | ❌ 0/0 | 0 | 0 | 0 |
| floci-299 | DynamoDB stream | ❌ 0/0 | ❌ 0/0 | 0 | 0 | 0 |
| floci-352 | — | ✅ 2/0 | ✅ 2/0 | 0 | 0 | 0 |
| grpc-java-12735 | xds CDS（gradle） | ❌ 0/0 | ❌ 0/0 | 0 | **1** | 0 |
| **合计** | | **2/6** | **2/6** | **0** | **1** | **0** |

**解决率不变：2/6 → 2/6。** 全 band 只有 1 次 hook 阻断（grpc 收尾门），台账零阻断。

> **勘误（2026-07-21）**：本报告初版合计误记为 3/6 → 3/6；per-instance 表与云机 `ab-hooks-results.tsv` 原始数据均为每臂 2 条 resolved（floci-164 + floci-352）。A/B 差值为零的结论不受影响。

### 过程指标（每实例：轮数 / 写 / 验证命令 / 收尾时是否已验证）

| 实例 | arm | 轮数 | 写 | 验证命令 | 收尾不测试 |
|---|---|---|---|---|---|
| floci-164 | ctrl / hooks | 27 / 31 | 3 / 7 | 8 / 6 | no / no |
| floci-248 | ctrl / hooks | 23 / 15 | 6 / 4 | 5 / 2 | no / no |
| floci-88 | ctrl / hooks | 50 / 22 | 8 / 1 | 13 / 4 | no / no |
| floci-299 | ctrl / hooks | 26 / 28 | 2 / 3 | 1 / 6 | no / no |
| floci-352 | ctrl / hooks | 13 / 14 | 2 / 3 | 1 / 2 | no / no |
| grpc-12735 | ctrl / hooks | 36 / 37 | 2 / 2 | 15 / 14 | no / no |
| **合计** | ctrl | | | | **收尾不测试 0/6，收尾已验证 6/6** |

## 2. 关键发现（诚实、反直觉）

### 发现 1：Phase 0 配置下 Cafecli 已经是「验证驱动」——收尾门几乎无处可咬

对照臂 **6/6 都在最后一次写入后运行了验证命令**（`ended_without_testing = 0/6`）。抽查 ctrl 臂的实际命令：floci-164 跑了 `./mvnw test -Dtest="S3ServiceTest"`、floci-248 跑了 `./mvnw test -Dtest=AcmImportExportTest`——都是**针对性单测**，不是只 compile。

这直接**修正**了 2026-06-08 的定性论断「Cafecli 几乎从不跑测试（jackson/164/248 均 0 次）」：那个「不测试」是**紧配置的产物**——no-edit + 50 轮硬帽下，floci-164 在 `mvn compile` 处就撞墙、根本没走到测试阶段。**一旦 `edit_file`（外科编辑省轮数）+ 80 轮把预算放开，agent 自然就会在写后跑测试**。也就是说，Phase 0 的两个免费旋钮，除了救回 build-break，还**顺带把「收尾不测试」这一失败形状消解掉了**。

### 发现 2：收尾门仍然在它该咬的地方咬了一口（grpc-12735）

grpc hooks 臂触发了全 band 唯一一次收尾门阻断：

> 「你本回合修改了文件，但最后一次写入之后没有运行任何验证命令。请在工作区内运行相关测试或构建命令（如 mvn test / gradle test / npm test / pytest / go test）自证改动可用后再结束；不要凭假设交付。」

该臂的轨迹里 agent 在跑完测试后又做了一次**收尾前的补丁编辑**、然后想直接结束——收尾门在 STOP 点拦下、要求它对最后这次编辑再验证一次，agent 照做（该臂末态 `ended_without_testing = no`）。这是**机制按设计工作的干净演示**：它精确命中「写后未验证」的语义、把一次「本会将凭假设交付」的收尾转成了「验证后收尾」，且**没有改变评分结果**（grpc 仍 0/0，真实失败是模型层错修，见发现 4）。

对照臂 grpc 恰好没留下收尾前的悬空编辑（15 次验证命令、末态已验证），所以不需要拦——两臂差异来自 LLM 轨迹的非确定性，收尾门只在**处理臂那条恰好会裸奔的轨迹**上兜住了它。这正是「守卫 = 确定性保证」而非「行为改造器」的含义。

### 发现 3：读前写台账零阻断——Phase 0 配置下 agent 已经先读后写

台账全 band 0 阻断，说明对照臂 agent 在改已存在文件前都先 `read_file` 了（或是新建文件）。这与发现 1 同源：`edit_file` 的工作流（需精确 `old_string`）本就诱导 agent 先读。台账的价值同样落在**保证**而非改造：紧配置 / 整文件 `write_file` 回归时，它才会开始拦盲写。

### 发现 4：残余失败属模型天花板 / safe-edit 领域，非本两租户的靶点

4 条未解（248/88/299/grpc 全 0/0）逐条归因与 Phase 0 一致：**88/248 = agent 写了 `src/test/**` 与隐藏 gold test_patch 冲突（safe-edit #04 的靶点，非本租户）；299/grpc = 编译正常但错修（模型天花板）**。收尾门 / 台账都不针对这两类，所以解决率不动是**预期内且诚实**的结果——本 band 的解决率杠杆是 safe-edit，不是这两个租户。

## 3. 「收尾不测试」量化（历史回归带 noedit vs Phase 0）

> 反作弊红线：检测器**只看 agent 自己在工作区跑的命令**（写类工具 + `execute_command` 命令串匹配一个通用的 build/test 正则，逐字移植自 `FinishGateTenant.DEFAULT_VERIFY_REGEX`），**从不引用 gold patch / FAIL_TO_PASS / 任何评分测试集**，也不按实例参数化。这与收尾门在产品里的判定同源。

| 配置 | 硬轮数 | edit_file | hooks | 收尾不测试 | 收尾已验证 |
|---|---|---|---|---|---|
| **noedit（紧配置，`edit_file` off）** | 50 | ✗ | off | **1/6**（floci-88） | 5/6 |
| **ctrl（Phase 0）** | 80 | ✓ | off | **0/6** | 6/6 |
| **hooks（Phase 0 + 租户）** | 80 | ✓ | on | 0/6 | 6/6（收尾门兜底 1 次） |

每实例 noedit 明细（轮数/写/验证/收尾不测试）：164=50/3/1/no · 248=18/4/4/no · **88=23/5/5/YES** · 299=26/3/1/no · 352=15/3/3/no · grpc=19/1/1/no。

> **诚实校准**：noedit 臂用**当前 hooks jar 关掉 `edit_file` + 默认 50 轮**复现紧配置，不是 2026-06 那把 Jun-6 jar。结果 **1/6** 明显低于 6-08 定性论断（floci-164/248 当时 0 次测试）——**因为当前 agent 本身在这一个月里变得更验证驱动了**（提示词 / 预算 / edit_file 都在演进）。所以：这个失败形状在**历史上普遍**（6-08 实测）、在**当前 agent 上已很稀有**（跨配置 0–1/6）。唯一现存实例 floci-88 是干净样本：写→测→写→测→…最后一次写后**没再测**就想收尾——正是收尾门的靶心（若该臂开 hook，会被拦下要求再测）。

**读法**：两条路径都能压掉「收尾不测试」——(a) 免费旋钮 `edit_file`+更大预算 + agent 自身演进（顺带让 agent 有空间且倾向自测），(b) 收尾门（把「先验证再交付」变成**不依赖预算宽裕、不依赖 agent 当下脾气的硬保证**）。在已被 (a) 压到 0/6 的 band 上，收尾门是**冗余的安全网**，其独有价值是**可移植的确定性**：预算收紧 / 编辑模式回退 / 未来模型回归时，不变量仍然成立——它在 grpc 上真实兜住了 1 次就是证明。

## 4. 结论与默认值建议

- **Phase 0 band 上，两个内建租户不改变解决率（2/6 → 2/6，见勘误）**，因为它们针对的失败形状（收尾不测试、盲写/陈旧写）已被 Phase 0 的免费旋钮 + agent 自身演进消解到 0/6。**这是诚实的近似零结果，不是负面结果**——它把 ADR 0012 的租户从「拍脑袋加的检查」钉成了「在正确配置下确实无害、且在失败形状出现时确会兜底」的确定性保证。
- **收尾门在 grpc 上真实兜住了 1 次「写后未验证」的收尾**，证明机制在 headless `run` 全链路（构造期注册 → Stop 分发 → 阻断回灌 → 续跑）跑通、判定完全 instance-agnostic、且**零误伤**（对照臂本会自测的 6 条全程没被打扰，台账 0 阻断）。
- **默认值决定（回答 ADR 0012「eval 出证据后再翻默认」）：本 band 证据不支持翻默认——保持两租户默认关。** 它们在已经验证驱动的 agent 上无解决率增益，只有确定性保证价值；翻默认需要一个「失败形状仍普遍」的 band 或「收尾门确把 fail 转 resolve」的正例，本期都不成立。
- **解决率的下一个杠杆是 safe-edit（#04）**，不是这两个租户——88/248 的底层生产修复正被 `src/test/**` 自伤强制 0/0。这与 Phase 0 的 go/no-go 结论一致。

## 5. 复现骨架（脚本已入库 `eval/swebench-live/scripts/`，在云机 `~/swebench/` 执行）

- `cafecli-hooks.jar`（`feat/configurable-hooks` HEAD，Java 21 shaded，md5 `f85316e1636bf5705326eb81099ffc26`）——两臂共用。
- `csolve_cafecli_ab.sh`（`CAFECLI_ARM=ctrl|hooks`，JDK21 启动 + 硬轮数 80 + edit_file on；hooks 臂加 5 个 `-D`；`-Dcafecli.audit.dir=/out/audit`）。
- `solve_one_ab.sh <iid> <arm>` / `run_ab_hooks.sh`（6×2，resumable，结果 → `ab-hooks-results.tsv`，trace+audit+patch 归档 → `ab-hooks/<arm>/<iid>/`）。
- `csolve_cafecli_noedit_trace.sh` / `run_noedit_trace.sh`（同一 `cafecli-hooks.jar` + `-Dcafecli.edit_file.disabled=true`，默认 50 轮，solve-only，trace → `ab-hooks/noedit/<iid>/`）。
- `analyze_ab.py`（instance-agnostic 收尾不测试检测器 + 审计 hook_block 计数；正则逐字移植自 `FinishGateTenant`）。
- 验收依据：`~/SWE-bench-Live/logs/<arm>-<iid>/<iid>/report.json`（resolved + F2P/P2P）；`ab-hooks/<arm>/<iid>/audit/audit-*.jsonl`（hook_block 归因）。
