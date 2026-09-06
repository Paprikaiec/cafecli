# 01 — band baseline：拿到当前版本的绝对解决率

Status: **ready-for-human**
Type: 评测运行（需云机机时）

## Parent

`.scratch/eval-baseline-refresh/PRD.md` · ADR 0013

## 问题

所有现存报告给的都是**相对**量：Δ解决率、过程指标、McNemar p 值、Wilson 区间。没有一处能回答面试官必问的那句「所以它现在能解多少？」

## 要做的

在 measurement band v1 的**可准入子集**（ADR 0013 修订 1：实际 15–16 条，非名义 20 条）上跑一次当前 HEAD 的单臂 baseline，产出一个可对外引用的解决率。

- 配置走 canonical band wrapper（含 `-Dcafecli.safe_edit.enabled=true`，2026-07-20 决定）
- 硬轮数 80、`edit_file` on、hooks 默认关（与产品默认一致，不为出数字开特殊开关）
- 统一 deepseek-v4-pro，官方 harness 评分
- ≥2 次重复，报 Wilson 区间而非裸点估计——小样本下点估计会被误读成稳定通过率

## 验收

- `reports/` 下新增一份报告，首行给出 `解决率 X/N（Wilson 95% CI [a, b]）`，**N 写实际使用的 roster 大小**
- repo-clustered 报告（floci 占比高，不做聚类会高估）
- 每条实例的 `report.json` 归档，可复查

## 反作弊红线

沿用 `2026-07-14` 报告的纪律：任何判定只看 agent 自己在工作区跑的命令，**从不引用 gold patch / FAIL_TO_PASS / 任何评分测试集**。

## 成本

1–2 天机时。镜像层共享后 floci 系实例拉取很快，主要开销在 solve 轮次。

## Comments

- 2026-09-06：为简历/对外材料立项。简历当前不依赖此数字（用的是闭环叙事），拿到后可增量更新。
