# 03 — safe-edit A/B 重复数提到 4–5 轮

Status: **ready-for-human**
Type: 评测运行（需云机机时）

## Parent

`.scratch/eval-baseline-refresh/PRD.md` · `reports/2026-07-20-repeated-safe-edit-ab.md`

## 问题

2026-07-20 的结果是**过程指标显著、主指标不显著**：

| 指标 | control → safe-edit | 统计 |
|---|---|---|
| 补丁含测试改动 | 8/12 → **0/12** | 精确 McNemar **p=0.0078** ✅ |
| harness patch-apply 失败 | 5 → **0** | — |
| **解决率** | 3/12 (25%) → 4/12 (33.3%) | 1 safe-only / 0 control-only，**p=1.0** ❌ |

解决率只有 1 对不一致配对，样本量根本不足以产生显著性。方向是对的（safe-only 赢 1、control-only 赢 0），只是证据量不够。

## 要做的

同一 band（6 条 Phase-0 core-fail）、同一唯一变量（`-Dcafecli.safe_edit.enabled`），重复数从 2 提到 **4–5**，即 48–60 个 solve+eval case。保持臂序反转以抑制时间漂移。

## 验收

- 不一致配对数 ≥5 才有机会做出显著性；**若仍不显著，如实报告**，不改统计口径去凑 p 值
- 继续用 Wilson 区间报臂级比例

## 值不值得做

中等优先级。过程指标那条（8/12 → 0/12, p=0.0078）已经足够写进简历且无懈可击；主指标做显著是锦上添花。若机时紧张，让位给 `01`。

## Comments

- 2026-09-06：简历用的是过程指标那条，不依赖本票。
