# 04 · CRLF + BOM 无损 round-trip

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/edit-file-transaction/PRD.md`

## What to build

让编辑事务对行尾与 BOM 做**无损**容忍（ADR 0002 的无损部分）：匹配与重建在「BOM 剥离 + LF 归一」内容空间进行，写盘时还原文件原始的主导行尾（CRLF/LF）与 BOM。模型用 LF 写的 `old_string` 能命中 CRLF 文件；编辑后文件保持原行尾与 BOM 不变。diff 观察者收 **LF 空间**的 before/after（干净 diff），磁盘写回带原始行尾/BOM。

## Acceptance criteria

- [ ] CRLF 文件 + LF `old_string` 能命中并成功编辑；编辑后磁盘字节仍为 CRLF。
- [ ] 带 BOM 文件被编辑后仍保留 BOM；BOM 不参与匹配。
- [ ] 纯 LF 文件行为不变（回归）。
- [ ] 观察者 `[before, after]` 为 LF 空间；磁盘终态为原始行尾/BOM（断言磁盘字节）。
- [ ] 超 5MB 上限判定基于写盘内容（含还原后的行尾/BOM）。

## Blocked by

- `.scratch/edit-file-transaction/issues/02-edit-transaction-core.md`
