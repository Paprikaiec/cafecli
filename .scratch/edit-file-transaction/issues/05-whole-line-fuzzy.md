# 05 · whole-line region-local fuzzy（ADR 0002 fuzzy）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/edit-file-transaction/PRD.md`

## What to build

在 exact 匹配失败时，对**单独一行**做模糊容忍回退（ADR 0002 的刻意偏离 pi 部分）：`old_string` 归一后（NFKC + 尾随空白 strip + 智能引号 / 破折号 / 全角空格 → ASCII）若等于**某一整行**，则命中该行；**只替换那一行、文件其余字节保持不变**（region-local，绝不像上游那样因一处模糊命中就整文件归一）。`old_string` 跨多行而需走模糊时直接报错（只匹配一行；`new_string` 仍可任意多行）。结果文本显式标注哪条 edit 用了模糊匹配、命中第几行。模糊命中同样参与唯一性 / `replace_all` 与跨 edit 重叠检测，exact 永远优先于 fuzzy。

归一仅用于「整行 与 `old_string`」的相等比较，绝不对全文件做 lossy 重写。

## Acceptance criteria

- [ ] 仅引号 / 尾随空白 / 破折号 / 全角空格 / NFKC 差异的单行 `old_string`，exact 失败后经整行模糊命中并成功编辑。
- [ ] 模糊编辑只改命中那一行；同文件其它含尾随空白 / 智能引号的行字节不变（断言磁盘）。
- [ ] 多行 `old_string` 走模糊 → 报错、文件不变。
- [ ] `new_string` 为多行时，命中的一行被替换成多行（插入）成功。
- [ ] 结果文本标注该条为模糊匹配并给出命中行号。
- [ ] 模糊命中 >1 行且非 `replace_all` → 歧义报错；`replace_all` → 全部命中行替换；与其它 edit 重叠 → 报错。
- [ ] exact 可命中的场景绝不触发模糊（exact 优先）；模糊用例多跑几次确认非侥幸。

## Blocked by

- `.scratch/edit-file-transaction/issues/04-crlf-bom-roundtrip.md`
