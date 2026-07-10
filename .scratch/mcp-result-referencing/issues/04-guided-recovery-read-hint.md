# 04 — 引导恢复：read-hint（jq-probe → grep_code + read_file offset）+ 端到端取回深层字段

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/mcp-result-referencing/PRD.md`（ADR 0009）

## What to build

让引用真正可用：digest 附一行读取指引，并端到端证明模型能精确取回落盘文件里、digest 取样之外的深层字段。不新增 `read_mcp_result`——复用现有读回路。

- 读取指引（恢复链）：`jq` 优先（结构化查询，仅在 `McpResultPersister` 一次性探测到 `jq` 可用时才建议）→ `grep_code` + `read_file`(offset) 兜底（始终可用，即 CC 的 “search for specific content”）。
- 端到端证明：把根因字段刻意放在 digest 取样之外（如第 200 条 network request 的 `requestId`），模型经 `jq` 取回；模拟 `jq` 缺失时，经 `grep_code` 定位 + `read_file`(offset) 取回同一字段。
- 读侧经 `PathGuard.resolveSafe` 天然可读根内结果目录，无需放宽 PathGuard；落盘是内部直写、不过写侧 Guard。
- 建议排在 01（read_file 加固）与 03（富 digest）之后，恢复体验才完整——但硬依赖只有 02。

可演示（测试）：`InMemoryTransport` 端到端——大结果落盘后，用 `jq` 查询断言取回深层字段；模拟 `jq` 缺失，用 `grep_code` + `read_file`(offset) 取回同一字段；read-hint 文案随 `jq` 探测结果裁剪（有则含、无则不含）。

## Acceptance criteria

- [ ] digest 含读取指引：`jq`（探测可用才建议）→ `grep_code` + `read_file`(offset) 兜底
- [ ] `jq` 探测一次并缓存；不可用时指引不含 `jq`
- [ ] 端到端：digest 样本外的深层字段可经 `jq` 取回
- [ ] 端到端兜底：`jq` 缺失时经 `grep_code` + `read_file`(offset) 取回同一字段
- [ ] 读侧经 PathGuard 天然允许（结果目录在项目根内），无需放宽 PathGuard
- [ ] 测试（seam）：`InMemoryTransport` 端到端恢复（`jq` 路径 + `jq` 缺失兜底路径）

## Blocked by

Issue 02（引用化 tracer bullet）；建议在 01、03 之后
