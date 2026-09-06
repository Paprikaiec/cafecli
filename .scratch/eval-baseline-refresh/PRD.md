# 评测基线刷新（eval-baseline-refresh）

Status: **ready-for-human**（需云机机时，本地无法完成）
Type: 评测基础设施 / 对外证据

## 为什么

两个缺口，都在「对外讲这个项目」时才暴露，日常开发不影响：

1. **缺一个绝对数字。** 现有的 5 份报告全是配对 A/B 的**相对**收益（Δ解决率、过程指标、McNemar p 值），没有任何一处回答「当前版本在干净实例带上解决率是多少」。相对收益能说服懂评测的人，绝对数字才能说服所有人——简历、README、面试第一分钟都需要后者。
2. **vs Claude Code 的对比已经过期。** `reports/2026-06-08-cafecli-vs-claude-ab.md` 记录的 2/9 vs 4/9 是 **Phase 0 之前的 Cafecli**。此后 `edit_file` + 80 轮救回 `floci-164`、safe-edit 让 `floci-248` 在 treatment 臂 2/2 解出——**当时落败的两条现在都已稳定解出**，那份报告的结论已不代表现状，但它仍是仓库里唯一一份对外可引用的横向对比。

## 不做什么

不为了好看去调 band 组成或放宽 gold 准入。ADR 0013 修订 1 已经确认干净池接近枯竭（N=20 不可达，实际操作 15–16），**报告实际使用的 roster 大小**这条纪律不因为要出数字而松动。

## 票

- `issues/01-band-baseline-absolute-number.md` — 跑一次 band baseline 拿绝对解决率（**最高价值**）
- `issues/02-rerun-claude-comparison.md` — 9 条实例重测 vs Claude（有公平性陷阱，读票再决定）
- `issues/03-safe-edit-more-repeats.md` — safe-edit 重复数提到 4–5 轮，尝试把主指标做显著

## 时间性

2026-09-06 决定 **defer**：求职期优先把简历发出去，这些排到面试排期的空档做。真跑出结果后简历可增量更新，面试时作为「上周刚跑完」的新证据反而更有力。
