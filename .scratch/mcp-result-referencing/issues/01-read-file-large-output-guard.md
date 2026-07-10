# 01 — read_file 全局加固（裸读 throw-nudge）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/mcp-result-referencing/PRD.md`（ADR 0009）

## What to build

给 `read_file` 工具加一层全局防线：**不带 `offset`/`limit` 的裸读**若命中的文件过大，直接抛出 error-nudge，而不是把整文件 dump 进热上下文。这是通用改进（利好一切大文件读取），也顺带让后续 [[MCP 结果引用化]] 无法被模型裸读绕过。

- 判定：仅对 unranged 读做一次 byte-size 预读 stat；文件 > ~256KB → 抛 error-nudge，文案指引改用 `offset`/`limit` 分页或 `grep_code`/`jq` 精确检索、勿整文件读取。
- ranged 读（带 offset/limit）行为不变：仍 ≤2000 行、末尾自带“已截断，可用 offset=X 继续读取”。
- 不 dump、不 truncate（对标 Claude Code：截断会抬高 mean tokens，抛错的 ~100 字节更省——其 2026-03 截断实验已回退）。
- 用 byte/char，不引入 API token 计数。cap 经 system property / env 可配置，默认 ~256KB。

可演示（测试）：裸读一个 >256KB 临时文件 → 抛 error-nudge（断言文案含 offset/limit 与检索指引）；裸读小文件 → 正常返回；ranged 读大文件 → 正常分页 + 续读提示。

## Acceptance criteria

- [ ] unranged 读 > ~256KB → 抛 error-nudge（含改用 offset/limit 或 grep_code/jq 的指引），不 dump、不 truncate
- [ ] ranged 读（offset/limit）行为不变，≤2000 行 + 续读提示
- [ ] 判定基于 byte-size 预读 stat，不引入 API token 计数
- [ ] cap 可配置（system property / env），默认 ~256KB
- [ ] 测试（seam）：`ToolRegistryTest`（现已覆盖 read_file）扩展——裸读超阈值抛错、小文件正常、ranged 大文件正常分页

## Blocked by

None - 可立即开始
