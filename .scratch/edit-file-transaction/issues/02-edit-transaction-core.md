# 02 · 编辑事务核心：edits[] schema + 事务化重写 + 引导文案

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/edit-file-transaction/PRD.md`

## What to build

把 edit_file 从顶层单编辑 reshape 成**编辑事务**：入参收敛为 `{path, edits[]}`，顶层 `old_string`/`new_string` 移除。edit_file 脱离 `fromMap`、原生消费 `JsonNode` 并反序列化成 typed 请求对象。

编辑事务语义（ADR 0002 + pi 蓝本；本切片只做 exact 匹配，CRLF/BOM/fuzzy 留给切片 04/05）：所有 `old_string` 对**同一份原始内容**匹配；每条 `replace_all=false` 须唯一命中、`true` 展开全部非重叠命中；所有命中区间排序后做**严格**重叠检测（相邻允许、相交报错）；全量校验通过后一次性应用、单次写盘——**全有或全无**，任一处 0 匹配 / 歧义 / 重叠 / 缺参都整批不写盘、文件不变、报错点名第几条（1-based）。空 `new_string` = 删除；缺 `new_string`(key) = 报错；`old_string` 为空 → 指向 write_file；`edits:[]` → 报错；old==new 或整体应用后无变化 → 报错；写盘内容超 5MB 上限 → 策略拒绝。

复用并验证既有设施在 edits[] 形态下仍成立：文件互斥锁（ADR 0001）罩整个读改写、diff 观察者**一次** `[before, after]`、LSP 钩子放锁后仅成功时触发、审计 allow/deny 与 HITL 审批与 write_file 对齐、A/B 系统属性仍能整体移除 edit_file（含 schema）。

引导文案：工具描述改 pi 式 guideline（同一文件多处修改用一次调用的多条 edits[]，不要分多次调用）；base 模式系统提示把「分轮顺序进行」改为「一次调用多条 edits[]、一次提交」。AGENTS.md 核心工具数仍 12。

schema 形状（编码自设计决策）：

```
{ path: string (required),
  edits: array (required, 非空) of {
    old_string: string (required),
    new_string: string (required),   // 显式空串 = 删除
    replace_all?: boolean (default false) } }
```

## Acceptance criteria

- [ ] edit_file schema 为 `{path, edits[]}`，`edits` 项必填 `old_string`/`new_string`、可选 `replace_all`；顶层 old/new 不再出现在 schema。
- [ ] 一次调用多条 disjoint edits 全部落盘；返回含「共 N 条编辑 / 替换 M 处」+ 紧凑 diff（超长截断）。
- [ ] 批内任一条 0 匹配 / 歧义（非 replace_all 多命中）/ 区间重叠 → 整批不写盘、文件不变、报错点名第几条。
- [ ] `replace_all=true` 展开全部非重叠命中并参与跨 edit 重叠检测；`false` 要求唯一命中。
- [ ] 空 `new_string` 删除；缺 `new_string` 报错；`old_string` 空 → 指向 write_file；`edits:[]` 报错；整体无变化报错。
- [ ] 文件不存在 → 指向 write_file；路径越界与超 5MB → 策略拒绝（审计 deny）；普通业务失败审计 allow。
- [ ] 同文件两条 edit_file（工具批次）经文件互斥锁串行（提交链：第二次 before == 第一次 after）、异文件并行；观察者一次 `[before, after]`。
- [ ] 工具描述含 pi 式「一次调用多条 edits[]」引导；write_file 仍引导优先 edit_file；base 提示同步；A/B 开关下 edit_file 及其 schema 消失；AGENTS.md 数量 12、`DocumentationSyncTest` 绿。
- [ ] 现有单编辑测试改用只含一条的 `edits[]` 形态后全绿（含 `HitlToolRegistryTest` 内联 JSON、schema/描述断言）；新增多编辑 / 重叠用例多跑几次确认非侥幸。

## Blocked by

- `.scratch/edit-file-transaction/issues/01-jsonnode-input-channel.md`
