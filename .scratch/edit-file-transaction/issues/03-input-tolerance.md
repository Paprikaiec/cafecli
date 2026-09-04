# 03 · 入参容忍：stringified edits + 顶层 old/new 折叠

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/edit-file-transaction/PRD.md`

## What to build

在反序列化前加一个容忍步骤（pi `prepareArguments` 等价），提升对模型序列化习惯差异的鲁棒性，不改变编辑事务语义：

- 当模型把 `edits` 发成 JSON **字符串**（部分模型会这样）时，解析成数组再执行。
- 当模型沿用旧的顶层 `old_string`/`new_string` 单编辑写法时，折叠成一条追加进 `edits`。

## Acceptance criteria

- [ ] `edits` 为 JSON 字符串时被解析为数组并正常执行编辑事务。
- [ ] `edits` 为非法 JSON 字符串时不崩、返回可理解的参数错误、文件不变。
- [ ] 顶层 `old_string`/`new_string`（无 `edits`）被折叠成一条 edit 并正常执行。
- [ ] 顶层与 `edits[]` 同时出现时按既定规则合并（顶层追加为一条），行为确定可测。
- [ ] 经 `executeTool` 断言上述容忍；切片 02 的全部用例仍全绿。

## Blocked by

- `.scratch/edit-file-transaction/issues/02-edit-transaction-core.md`
