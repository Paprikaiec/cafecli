# 01 · JsonNode 入参通道 + fromMap 适配（ADR 0003）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/edit-file-transaction/PRD.md`

## What to build

把内置工具的执行器契约从「接收拍平后的 `Map<String,String>`」改为「接收解析后的 `JsonNode`」，让工具批次分发不再对内置工具做 `asText()` 拍平——数组/对象参数不再被压成空串。这是 ADR 0003 的落地，也是编辑事务接收 `edits[]` 的前置。

本切片**零行为变化**，纯通道替换：所有现有只吃标量参数的内置工具（read_file / write_file / list_dir / glob_files / grep_code / execute_command / create_project / search_code / web_search / web_fetch / browser_* / load_skill / save_memory / revert_turn，以及当前仍是单编辑形态的 edit_file）一律经一个 `fromMap` 适配器注册，适配器内部仍做与改造前同款的 `asText()` 拍平。MCP 工具不受影响（本就走独立的原始 JSON invoker 通道）。

验证标准是「所有现有工具行为字节级不变」——纯回归，不新增测试缝。

## Acceptance criteria

- [ ] 内置工具执行器契约改为接收 `JsonNode`；工具批次分发对内置工具不再 `asText()` 拍平。
- [ ] 所有现有内置工具经 `fromMap` 适配器注册，`executeTool` 行为与改造前完全一致（现有 `ToolRegistryTest` 全绿、无需改断言）。
- [ ] 现有 edit_file（单编辑、replace_all、删除、错误路径、审计、HITL、A/B 开关）经 `fromMap` 保持原行为，相关测试全绿。
- [ ] MCP 工具路径不受影响（现有 MCP / `HitlToolRegistryTest` 全绿）。
- [ ] `ToolExecutor` 改造后仓库内无外部实现/调用破裂；`mvn -Pquick test` 全绿。

## Blocked by

None - can start immediately.
