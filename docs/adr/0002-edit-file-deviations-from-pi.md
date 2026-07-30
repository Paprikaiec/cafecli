# edit_file 在两处刻意偏离 pi 的 edit 工具

Cafecli 的 `edit_file`（编辑事务）以 pi agent 的 `edit` 工具 + `applyEditsToNormalizedContent`（`earendil-works/pi`，`packages/coding-agent/src/core/tools/edit.ts` / `edit-diff.ts`）为蓝本，但在两处刻意偏离上游。记录于此，避免后人“好心”把它改回 pi 原样。

## 决策

1. **保留 `replace_all`（pi 没有）。** pi 每个 `oldText` 必须唯一匹配，`>1` 即 duplicateError，没有任何批量替换开关。Cafecli 沿用 claude-code 语义，给每条 edit 一个可选 `replace_all`（默认 false）：false 要求唯一匹配，true 把该条的所有非重叠匹配展开成多个区间。理由：跨多处相同符号的批量改名是高频 SWE-bench 修复形态，uniqueness-only 会逼模型给每处补上下文（费 token、易碎）或直接失败。`replace_all` 展开后的区间照样参与跨 edit 重叠检测，仍是全有或全无。

2. **fuzzy 匹配限定为 whole-line + region-local（pi 是整文件 lossy 归一）。** pi 一旦有任意一条 edit 走 fuzzy（智能引号 / 破折号 / 尾随空白 / NFKC 容忍），就把**整个文件** `normalizeForFuzzyMatch` 后写回——静默抹掉所有行尾随空白、把全文引号 ASCII 化，是 lossy、blast radius 极大的副作用。Cafecli 改为：fuzzy 仅作为 exact 失败后的回退，且只在 `old_string` 归一后**等于某一整行**时命中（多行 `old_string` 直接报错），只替换那一行，文件其余字节保持不变。CRLF / BOM 是无损 round-trip 归一，对全文安全，照旧全文处理。

## 后果

- Cafecli edit_file 是 pi 语义的超集（多 `replace_all`）+ 子集（fuzzy 不做 sub-span、不做整文件归一）。
- 模型若发来仅靠 sub-span fuzzy 才能命中的 `old_string`，会报错要求 exact 或整行——可接受的安全代价。
- 结果文本显式标注哪条用了 fuzzy（pi 不标），便于模型自查 lossy 命中。
- `old_string`（match）限一行；`new_string` 可任意多行（插入不受限）。
