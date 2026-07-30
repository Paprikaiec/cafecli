# Phase 0 再基线 — edit_file ON + 硬轮数 80（生命周期 Hook 守卫的 go/no-go）

- 背景：失败归因（[2026-06-08 A/B](2026-06-08-cafecli-vs-claude-ab.md) + `/tmp/handoff-cafecli-failure-attribution.md`）把可修失败抽象成三类守卫——compile-gate / safe-edit / self-check（ADR 0008，`.scratch/lifecycle-hook-seam/`）。**Phase 0 是写任何守卫代码前的零代码前置**：先用两个免费旋钮再基线，看哪些失败形状被 edit_file/更高轮数自然吃掉，再决定守卫做多深。
- 旋钮：**`edit_file` 开启**（之前 no-edit baseline 是显式关掉的变体）+ **`-Dcafecli.react.hard.max.iterations=80`**（硬轮数默认 50）。
- 数据：**SWE-bench-Live / MultiLang（java）**，6 条之前 no-edit baseline 上的 0/0 核心 fail（4 条 build-break + 2 条 Claude 解出而 Cafecli 没解的步数/定位 gap）。
- 臂：**Cafecli-only**（Claude / no-edit 基线已在云机存档），统一 **deepseek-v4-pro**，对称 prompt，官方 harness 评分。
- 单次运行（n=1/实例，floci-164 为 2/2），结论为**方向性 + 结构性归因**。

## 1. 结果对比表

| 实例 | 任务 | no-edit baseline | **Phase 0（edit_file+80t）** | Δ |
|---|---|---|---|---|
| **floci-164** | S3 multipart（3 文件） | ❌ 0/0（撞 50 轮硬上限） | **✅ resolved 1/0** | **救回 ⭐** |
| **floci-352** | — | ❌ 0/0（补丁破坏编译） | **❌ F2P=1/1（测试真的跑了）** | **build-break 消解** |
| floci-88 | S3 virtual-host filter | ❌ 0/0 | ❌ 0/0 | 无（根因转移，见下） |
| floci-248 | ACM import/export | ❌ 0/0 | ❌ 0/0 | 无（根因同 88） |
| floci-299 | DynamoDB stream | ❌ 0/0 | ❌ 0/0 | 无（编译正常，错修） |
| grpc-java-12735 | xds CDS（gradle） | ❌ 0/0 | ❌ 0/0 | 无（编译正常，错修/隔离） |

## 2. 逐条归因（决定守卫优先级的关键）

### ⭐ floci-164：步数上限 → 被 80 轮 + edit_file 救回
no-edit 撞 50 轮硬墙、修复不完整 → 0/0（Claude 用 52 轮解出，见 6-08 报告）。Phase 0：**11× edit_file / 0× write_file / 13× execute_command，<80 轮自然收敛，resolved 1/0，两次独立运行均解出（robust）**。归因：抬高硬轮数 + 外科 edit_file 两个免费旋钮直接修掉这一形状。

### ⭐ floci-352：edit_file 消解了 build-break
no-edit 是「Cafecli 整文件 write_file 覆盖 → 破坏编译 → 0/0」。Phase 0 改用外科 edit_file，**工程恢复可编译、测试真的跑起来（F2P=1/1，1 通过 1 失败）**——不再是 all-or-nothing 的 0/0，剩下的 1 个失败是推理/模型层。**这是 compile-gate 目标形状被 edit_file 自身吃掉的直接证据。**

### floci-88 / floci-248：根因转移到「写隐藏测试」→ 指向 safe-edit (#04)
两条仍 0/0，但根因**不是**生产源编译破坏，而是 agent **写了 `src/test/**`**，与 gold test_patch 冲突：
- **floci-88**（铁证）：agent **新建** `src/test/.../S3VirtualHostFilterTest.java`（与 gold 同路径）→ eval 打 gold test_patch 时 `error: …S3VirtualHostFilterTest.java: already exists in working directory` → 官方 F2P 测试装不进去 → 0/0。底下有 **97 行生产修复**（`S3VirtualHostFilter.java`）被白白抹掉。
- **floci-248**：agent 改既有隐藏测试 `…AcmImportExportTest.java` → gold test_patch reject → 0/0。底下有 **27 行生产修复**（`AcmJsonHandler` + `Certificate`）。

**禁写 `src/test/**`（safe-edit）会让 gold test_patch 干净落地、底层生产修复真正被评分**——这是本 band 上最高价值的守卫。注：评分对错是另一回事（248 仍可能定位偏），但至少不再被自伤强制 0/0。

### floci-299 / grpc-12735：编译正常、错修 → compile-gate 救不了
两条都只改生产源、无测试文件冲突、model.patch 与 gold test_patch **都 apply 成功**：
- **floci-299**：工程正常编译并跑了多个无关测试套件（`AwsRequestIdFilterIntegrationTest` 等绿），只有 DynamoDB F2P 目标无结果 → 修复错/不全。
- **grpc-12735**：大批 xds 测试编译并执行，只有 gold F2P 目标没产出 → gradle 测试隔离 / 错修（与历史归因一致）。

**关键：compile-gate 编的是 agent 自己的代码，本 band 6 条全部编译正常；它看不到 gold 隐藏测试，救不了任何一条 0/0。**

## 3. go/no-go 结论

| 守卫 | 本 band 证据 | 结论 |
|---|---|---|
| **edit_file + 硬轮数 80**（零代码旋钮） | 救回 164、消解 352 的 build-break | **收为 eval 默认** |
| **safe-edit (#04)** | 88 + 248 = 纯 `src/test/**` 冲突 0/0，底下有真修复 | **GO（最高价值）** |
| **compile-gate (#03)** | 6 个 0/0 无一是生产源编译破坏；build-break 形状已被 edit_file 消解 | **defer**（本 band 弱需求；ADR 0008 预判分叉成立） |
| **#02 Stop-hook seam** | 仅为承载 compile-gate；后者 defer | **defer**（除非当纯前向兼容 infra） |
| self-check (#05) prompt-nudge | 未单独测；164/352 已显示 agent 用 edit_file+exec 自检 | 可选、低成本，按需 |

残余失败（299、grpc、352 末测、88/248 底层修复对错）属**模型天花板 / 推理**，非脚手架——safe-edit 移除自伤后，剩下的就是纯能力问题。

## 4. 复跑骨架（云机 `~/swebench/`）

- `cafecli-editfile.jar`（current HEAD，Java 21 shaded，md5 `3f18554f8136a733f8e6ef4e13dbd8d8`，edit_file 默认开）
- `jdk21/`（portable Temurin 21 JRE，挂进容器 `/opt/jdk21` 启动 Java-21 jar；镜像自带 JDK 17 无法 load class 65）
- `csolve_cafecli_phase0.sh`（JDK21 启动 + `-Dcafecli.react.hard.max.iterations=80`，edit_file on；默认 java/JAVA_HOME 留给 agent 构建子进程）
- `solve_one_phase0.sh` / `run_phase0.sh`（`p0` arm，resumable，结果 → `phase0-results.tsv`，与既有 no-edit/claude 基线隔离不覆盖）
- 验收依据：`~/SWE-bench-Live/logs/p0-<iid>/<iid>/report.json`（`resolved` + F2P/P2P success/failure）
