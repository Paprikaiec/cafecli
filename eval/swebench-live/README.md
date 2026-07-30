# SWE-bench-Live 抗污染评测：Cafecli vs Claude Code

用 **SWE-bench-Live / MultiLang（java split）** 做 Cafecli 与 Claude Code 的 harness 对比。
相比默认 SWE-bench-java（疑被训练泄露），Live 按月更新，可取 deepseek-v4 训练截止后的实例。

- 两臂：**A = Cafecli**（`run` 子命令，root）／ **B = Claude Code**（headless `-p`，非 root `agent` 用户）
- 模型：两臂统一 **deepseek-v4-pro**（Anthropic 兼容端点）
- 评分：官方 SWE-bench-Live harness（`evaluation/evaluation.py`，依赖 `launch`=microsoft/RepoLaunch 子模块）
- 执行环境：云机 `100.126.237.3`（24c/31G/1.7T），镜像 `starryzhang/sweb.eval.x86_64.*` 预构建

## 已确认的核心结论（截至 2026-06-07）

- 闭环可用：`langchain4j-4890`、`helidon-11386` Cafecli 均 **RESOLVED**（抗污染 2026-04）。
- **同模型下两臂正确率高度趋同**；区分度更多落在**过程轴**而非正确率轴。
- 详例：[`reports/2026-06-07-jackson-5837-trajectory.md`](reports/2026-06-07-jackson-5837-trajectory.md) —— 两臂都 2/4，但
  Cafecli 4.4min/未跑测试/凭假设交付（+`writefile` 整文件覆盖摩擦），Claude 11.6min/$1.80/跑测试迭代/加测试。

## 失败归因分类（打平时比这个）

| 类别 | 含义 | 归因 |
|---|---|---|
| 定位失败 | 没找到/没改对文件 | harness 检索-导航 |
| 补丁落地失败 | 意图对但 diff 没 apply | 纯 harness（与模型无关） |
| 上下文丢失 | 长轨迹跑丢、重复劳动 | harness context 管理 |
| 过早收手 / 死循环到步数上限 | 调度与停止策略 | harness |
| 没用测试反馈 | 没跑测试或忽略红 | harness 策略 |
| 纯推理失败 | 模型确实想不出 | **唯一归因于模型**，对 harness 不提供信息 |

打平实例还要比**过程指标**：turn/token/成本、patch-apply 重试、定位步数、死路步数。

## 数据质量过滤（两道闸，避免误判）

1. **噪声静态过滤**（不用跑）：FAIL_TO_PASS 的测试类名必须出现在 `test_patch` 中，否则剔除。
   —— 剔掉如 `web3j-2254`（F2P 是 Nodesmith/Infura header 测试，gold 都修不过）。
2. **flaky/noisy gold 验证**：每条候选先跑 **gold patch**，要求 `resolved=True 且 PASS_TO_PASS 0 回归`，否则判 FLAKY 剔除。
   —— 剔掉如 `sofa-jraft-1249`（gold 自己也 39 个 NodeTest 回归 = 环境 flaky）。
   构建一次干净白名单（`whitelist.tsv`），之后 agent 只在白名单上跑。（脚本：`build_whitelist.sh`）

另需排除「新建类/feature/recipe」型任务（如 `openrewrite-7286`）：裸跑 agent 猜不中隐藏官方测试期望的精确类名 → 两臂都编译不过 → 非区分点。过滤：gold 源码 patch 应为**修改已有文件**（非 `new file mode`）。

## 定向采样：harness 敏感带（别盲搜）

只从 gold patch 元数据预筛：
- **改动 ≥2-3 个源文件 / 行数中上**的 long-horizon 任务 —— 最吃上下文管理与导航，harness 差异最易显形。
- 跳过 trivial（单文件小修，双方都过）与 hopeless（巨型重构如 jetty 155 文件，双方都挂）。
- 优先 **maven**（`.m2` 已缓存→eval 干净快），避开 gradle/polyglot（npm/dotnet 联网坑、F2P 易 0/0）。
- 当前 band 主力为 **floci**（io.github.hectorvent.floci，一个 Java AWS 本地模拟器；多文件 bug 丰富，且 14 个实例**共享镜像基础层**→拉一个慢、其余快）。
  候选清单见 `band.txt`（云机）。

> 备选：SWE-bench Pro 公开 split 每条都是 long-horizon（均改 107 行/4.1 文件），比在 Live 里大海捞针更高效——后续可评估切换。

## 云机脚本（`~/swebench/`）

| 脚本 | 作用 |
|---|---|
| `csolve_cafecli_live.sh` / `csolve_claude_live.sh` | 容器内单臂 solve（repo 在 `/testbed`，采 `git diff HEAD`） |
| `solve_one_live.sh <iid> <cafecli\|claude>` | 拉镜像+取 issue+跑 solve+收 patch |
| `eval_one_live.sh <iid> <arm>` | 用官方 harness 评分某臂的 patch |
| `run_claude.sh <iid>` | Claude 单臂 solve+eval+记录 |
| `batch_disc.sh` | 读 `cand.txt` 串行 pull→solve→eval（Cafecli）→ `disc-results.tsv` |
| `build_whitelist.sh` | 读 `band.txt` 串行 pull→gold→分类 → `whitelist.tsv`（过夜跑） |

公平性处理：Claude headless `-p` 默认会卡 plan mode 停在"待批准"——prompt 显式要求"直接改代码、勿只出计划"（SWE-bench 评测 Claude 的标准做法）；Claude 以 `agent` 用户跑并共享 `/root/.m2`（消除与 root Cafecli 的缓存不对称）。

## 基础设施约束

- 云机上行带宽 **~5-7MB/s**（实测，非镜像源/代理瓶颈），多 G 镜像 ~25min/个 → 同仓库实例共享 layer 是关键省时手段。
- Tailscale SSH 高负载易掉线（exit 255）→ 一切重活用云端 **nohup 串行驱动**独立跑，本地只发短命令+重试；`pkill` 用 `[b]atch_disc` 正则避免自杀；写文件用 scp。

## 下一步

1. `build_whitelist.sh` 过夜建白名单（gold 验证 band 候选）。
2. 在 CLEAN 白名单上跑两臂（Cafecli + Claude），按上表做失败归因 + 过程指标对比，找 pass/fail 区分点（或继续累积"打平但过程不同"的证据）。

## Repeated safe-edit A/B (2026-07)

`safe-edit` is shipped as an opt-in `PathGuard` rule. In the Phase-0 core-fail band,
`floci-88` and `floci-248` had previously scored forced 0/0 outcomes after Agent changes under
`src/test/**` collided with the gold test patch. This experiment changed only
`-Dcafecli.safe_edit.enabled=false|true` to test whether removing that self-harm produces a resolution
gain and whether rejecting temporary test edits harms Agent reasoning.

- Band: the six Phase-0 core-fail instances; two stochastic runs per arm, 24 solve+eval cases.
- Fixed: the same `cafecli-hooks.jar`, `deepseek-v4-pro`, symmetric prompt, 80-turn ceiling, hooks off,
  and official harness. Arm order reverses in repetition 2 to reduce time drift.
- Treatment: `ctrl=false`, `safe=true`; both arms pass the property explicitly.
- Process metrics: safe-edit policy denies, test-write attempts, test paths surviving in the patch,
  turns/tokens/wall time, and shell-command bypasses of the `write_file` / `edit_file` Guard.
- Statistics: paired safe-only/control-only outcomes with exact McNemar; Wilson intervals for arm-level
  proportions so the small-sample point estimates are not presented as a stable pass rate.

Results: [`reports/2026-07-20-repeated-safe-edit-ab.md`](reports/2026-07-20-repeated-safe-edit-ab.md).

- Resolution: control **3/12 (25.0%)** → safe-edit **4/12 (33.3%)**; 1 safe-only / 0 control-only
  pair, exact McNemar `p=1.0`. This is a positive signal, not a statistically supported pass-rate lift.
- Process: **8/12** control patches contained test changes versus **0/12** treatment patches; eight
  control-only pairs, exact McNemar `p=0.0078`. Observed harness patch-apply failures fell **5 → 0**.
- Decision: **GO for SWE-bench evaluation only**; keep the interactive product default off. The
  root-only `src/test/**` rule remains transitional. The deeper direction is repository-aware patch
  admissibility: temporary regression tests can aid reasoning but stay out of the scorer patch.

Cloud invocation:

```bash
# After uploading the four runtime/analysis scripts from scripts/:
nohup env REPEATS=2 PARALLELISM=2 bash ~/swebench/run_safe_edit_ab.sh \
  >~/swebench/safe-edit-ab-nohup.log 2>&1 &

python3 ~/swebench/analyze_safe_edit_ab.py \
  --root ~/swebench/safe-edit-ab \
  --json-out ~/swebench/safe-edit-ab-analysis.json
```

The driver is resumable. Results are in `~/swebench/safe-edit-ab-results.tsv`; complete per-run
artifacts (trace, audit, patch, official report, and solve/eval logs) are under
`~/swebench/safe-edit-ab/<arm>/r<repeat>/<instance_id>/`.

## Measurement band v1 (2026-07, ADR 0013)

Pass-rate work is now measured on a fixed 20-instance band instead of ad-hoc core-fail hunts
(rationale, admission rules, and rejected alternatives: `docs/adr/0013-measurement-band-with-known-solves.md`).

| Group | Instances |
|---|---|
| Core-6 (causal-debug subset, unchanged) | floci-164 · floci-248 · floci-88 · floci-299 · floci-352 · grpc-java-12735 |
| Tested solves (regression guards) | helidon-11386 · langchain4j-4890 · iflytek…skillhub-253 · smithy-3038 (+164/248/352 above) |
| Tested fails (headroom) | floci-57 · checkstyle-19487 · jackson-databind-5837 · helidon-11407 (long-horizon slot) · mage-14755 (+88/299/grpc above) |
| Fresh (3× gold admission — mostly REJECTED, see ADR 0013 Amendment 1) | nacos-14917 (2/3) · nacos-14778 (0/3, defective) · spring-boot-50119 (flaky P2P) · spring-framework-36550 · strimzi-12590 |

**Provisioning outcome (2026-07-26):** N=20 was not achievable — the clean pool is near-exhausted
rather than under-sampled, and the strict bar is retained so flaky PASS_TO_PASS tests cannot be
misread as agent-caused regressions. The operating band is the **admissible subset (~15–16)**;
always report the roster size actually used. Two of the four designated backups were already
known-bad in `whitelist.tsv` (opendataloader-386 FLAKY, jetty-14652 ERROR). A uniform 3× gold audit
of the 15 tested members (`run_band_tested_audit.sh`) is chained after the baseline, because those
members were admitted on 1× gold or less and 7 have no recorded gold run at all.

Constraints: all in the 2026-03/04 anti-contamination window; repo cap ≤6 (floci exactly at cap);
13 distinct repos. Backups if a fresh candidate fails gold admission: opendataloader-pdf-383/-386,
jetty-14652/-14748 (hard tier). Protocol per comparison: paired arms, ≥2 repetitions with arm-order
reversal, exact McNemar on discordant pairs, Wilson intervals, repo-clustered reporting, automatic
trajectory labels (localization / patch-apply / context-loss / irrelevant-verification /
iteration-exhaustion). The canonical band wrapper enables `-Dcafecli.safe_edit.enabled=true`
(2026-07-20 decision); full instance ids get pinned in `band.v1.txt` on the eval machine.
