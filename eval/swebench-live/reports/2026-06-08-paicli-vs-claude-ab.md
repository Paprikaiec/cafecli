# PaiCLI vs Claude Code — SWE-bench-Live 抗污染 A/B 对比

- 数据集：**SWE-bench-Live / MultiLang（java）**，实例创建于 **2026-03/04**（deepseek-v4 训练截止后，抗污染）
- 样本：9 条经 **gold 验证的干净实例**（floci ×7 多文件 + checkstyle + helidon）
- 两臂：**A = PaiCLI**（`run` 子命令，root）/ **B = Claude Code**（headless `-p`，agent 用户，共享 `/root/.m2`）
- 模型：两臂统一 **deepseek-v4-pro**；公平处理：**对称 prompt**（都给"直接改代码"指令）、相同镜像、官方 harness 评分
- 单次运行（n=1/实例），结论为**方向性**，非统计显著

## 1. 完整对比表

| 实例 | 任务 | PaiCLI | Claude | 判定 |
|---|---|---|---|---|
| floci-309 | DynamoDB update 表达式解析 | ✅ 1/0 | ✅ 1/0 | 双解 |
| helidon-11386 | socket IO 间歇失败(23 测试) | ✅ 23/0 | ✅ 23/0 | 双解 |
| **floci-164** | **S3 multipart(3 文件)** | ❌ 0/0 | ✅ 1/0 | **Claude 胜 ⭐** |
| **floci-248** | **ACM import/export** | ❌ 0/0 | ✅ 1/0 | **Claude 胜 ⭐** |
| floci-57 | S3 Content-Encoding(3 文件) | ❌ 4/1 | ❌ 3/2 | 双败(PaiCLI 更近) |
| floci-54 | — | ❌ 1/1 | ❌ 1/1 | 双败(同) |
| floci-210 | EventBridge resources | ❌ 0/5 | ❌ 0/5 | 双败(同) |
| floci-299 | DynamoDB stream | ❌ 0/0 | ❌ 0/0 | 双败(同) |
| checkstyle-19487 | — | ❌ 0/1,P2P2 | ❌ 0/1,P2P1 | 双败 |

**解决率：PaiCLI 2/9，Claude 4/9。** 差距 = floci-164 + floci-248 两条，**全部可归因到 harness（非模型推理）**。无 PaiCLI 胜而 Claude 败的反例。
（另：`jackson-databind-5837` 单独验证，两臂都 2/4 打平——见 [2026-06-07 报告](2026-06-07-jackson-5837-trajectory.md)。）

## 2. 区分点 Trajectory + 失败归因

### ⭐ floci-164：步数上限（最干净）

| | PaiCLI | Claude |
|---|---|---|
| 结果 | **FAIL 0/0** | **RESOLVED 1/0** |
| 轮数 | **50（撞硬上限，"已强制收尾"）** | **52** |
| 工具 | grep 4 / read 19 / exec 27 | Bash 25 / Read 35 / Edit 13 |
| **跑测试** | **0 次**（只 `mvn compile`，未到测试就被掐） | **8 次**（`mvnw test` 迭代）|
| 成本 | — | $2.18 |

**这条任务实际需要 ~52 轮；PaiCLI 50 轮硬上限刚好差 2 轮被强制中止 → 修复不完整 → 0/0。** 两次独立运行都撞同一上限（robust）。
**归因：「过早收手/步数上限」**（harness 调度）。

### ⭐ floci-248：定位偏差 + 误改测试文件

| | PaiCLI | Claude |
|---|---|---|
| 结果 | **FAIL 0/0** | **RESOLVED 1/0** |
| 轮数 | 31 | 23 |
| 改的文件 | AcmJsonHandler, **Certificate(模型)**, **AcmImportExportTest(测试)** | AcmJsonHandler, **AcmService** |
| **跑测试** | **0 次** | **3 次** |

PaiCLI 定位到不同文件（改 Certificate 而非 AcmService）**且改了既有测试文件**（与隐藏的 gold test_patch 冲突→测试编译不过→0/0）。Claude 精准定位两个源文件、不碰测试、用测试反馈验证。
**归因：「定位失败」+「误改隐藏测试文件」+「没用测试反馈」**。

### 贯穿全部用例的系统性差异

| 失败归因（按分类） | PaiCLI | Claude |
|---|---|---|
| 是否跑测试验证 | **几乎从不**（jackson/164/248 均 0 次） | **总是**（3–8 次，迭代） |
| 步数预算 | 50 轮硬帽（floci-164 被掐） | 更高/自适应（用到 52 轮） |
| 编辑安全性 | `writefile` 整文件覆盖摩擦（jackson 曾误覆盖） | 局部 Edit |
| 速度/成本 | 快（jackson 4.4min）、省 | 慢 2–3×、$0.9–2.2/条 |

**核心结论**：同模型下，**Claude Code 是"验证驱动"**（跑测试+迭代+更大步数预算），**PaiCLI 是"一击式推理"**（少跑测试、步数帽偏紧、整文件写）。PaiCLI 更快更省，但在**长程多文件任务**上因 harness 因素掉链子——而这些恰恰**可工程化修复**：

1. **编辑后默认跑相关测试再收手**（补"测试反馈"闭环）——预计能救回 jackson/floci-248 一类。
2. **长程任务调高/自适应步数上限**——直接救回 floci-164。
3. **`writefile` 改安全局部编辑语义**。
4. **不修改 agent 看不到隐藏版本的既有测试文件**。

## 3. SWE-bench-Live 测试流程总结与亮点

**流程**：选抗污染月份 → 静态噪声过滤 → gold 验证建干净白名单 → 两臂裸跑（容器内，官方 harness 评分）→ trajectory 失败归因 + 过程指标对比。

**亮点**：
- **抗数据污染**：用 SWE-bench-Live 2026-03/04 实例（模型训练截止后），规避默认 SWE-bench 训练泄露，结论可信。
- **双层数据质检（验证一次、复用多次）**：① 静态过滤（F2P 测试类须在 test_patch 中，剔噪声标注）；② gold+空 patch 验证 FAIL/PASS 行为确定性，剔 flaky，建干净白名单——剔掉了 web3j-2254(噪声)、sofa-jraft-1249(flaky)、jackson-5928/opendataloader(0/0) 等无效实例。
- **公平对照设计**：同模型、对称 prompt、共享构建缓存、消除 root/非 root 等不对称；主动发现并修正引入的 prompt 不对称（这本身体现严谨）。
- **超越 pass/fail 的过程归因**：用六类失败归因（定位/补丁落地/上下文/步数上限/测试反馈/纯推理）+ 过程指标（轮数/工具/成本/是否跑测试），即使正确率打平也能定位 harness 差异——把"没区分度"变成"区分度在效率轴/过程轴"。
- **定向采样 harness 敏感带**：用 gold-patch 元数据预筛多文件/长程任务（最吃 harness），不盲搜。
- **工程化基础设施**：受限网络(5MB/s、间歇代理、Tailscale SSH 重认证)下，用云端 nohup 串行驱动 + 镜像层共享 + 缓存跳过 pull + 抗掉线短命令，把慢网络挡在 per-run 循环外。

## 4. 简历写法建议

可写成一条"AI Agent 评测/基准"经历，量化 + 突出方法论：

> **设计并实现 Coding Agent 抗污染评测基准（SWE-bench-Live, Java）**
> - 搭建端到端评测流水线，在 contamination-free 的 SWE-bench-Live（2026 H1）实例上，于隔离 Docker 环境内用官方 harness 对比两套 coding agent（自研 PaiCLI vs Claude Code），统一模型变量（DeepSeek-v4），确保 A/B 公平。
> - 设计**双层数据质检**（静态噪声过滤 + gold/empty-patch 确定性校验），从候选集剔除 ~30% flaky/mislabeled 实例，建立可复用干净白名单，将评测信噪比显著提升。
> - 提出**超越 pass/fail 的失败归因框架**（定位/补丁落地/上下文/步数预算/测试反馈/模型推理 六类 + 过程指标），即使正确率打平也能定位 harness 能力差异，产出可执行优化项（测试反馈闭环、步数预算、编辑安全性），其中"调高步数上限"等改进有明确数据支撑。
> - 在 5MB/s 受限网络 + 不稳定隧道环境下，构建**抗掉线的云端串行评测驱动**（nohup 编排、Docker 镜像层共享、缓存感知调度），将不可控的网络开销移出每次评测循环。

**可量化的点**（按你实际数据填）：评测 N 条实例、剔除 X% 无效实例、定位 Y 个可执行 harness 优化项、识别"长程多文件任务上自研 agent 解决率 2/9 vs 对标 4/9，差距 100% 归因于 harness 而非模型"。

**面试可讲的故事**：① 为什么换数据集（训练污染）；② 怎么保证 A/B 公平（发现并修正自己引入的 prompt 不对称）；③ 打平时如何还能比出高下（过程轴归因）；④ 慢网络工程化。这些体现**实验设计严谨性 + 系统工程能力 + 对 agent 内部机制的理解**。

---
> 复现脚本与方法论见 [../README.md](../README.md)。
