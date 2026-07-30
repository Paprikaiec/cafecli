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
