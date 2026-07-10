# 内置工具入参走 JsonNode（typed），而非拍平的 Map<String,String>

引入 `edit_file` 的 `edits[]`（编辑事务）需要给内置工具传数组/对象参数，而原 `ToolRegistry.doExecuteTool` 把每个参数经 `JsonNode.asText()` 拍平成 `Map<String,String>` 再交给 `ToolExecutor.execute(Map)`——数组值被压成空串，结构化入参传不进去。

## 决策

把内置工具的执行器契约从 `String execute(Map<String,String>)` 改成 `String execute(JsonNode)`，`doExecuteTool` 不再拍平、直接把解析后的 `JsonNode` 交给执行器。原有只吃标量参数的遗留工具（read_file / grep_code / web_* 等）用一个 `fromMap(...)` 适配器包一层，内部仍做同款 `asText()` 拍平——把“有损拍平”从“对所有工具的默认”降级为“显式按需适配”，遗留行为字节级不变。`edit_file` 直接拿 `JsonNode` 并 `mapper.treeToValue` 成 typed DTO（`EditFileRequest` / `Edit`）。MCP 工具不受影响（本就走独立的 `Function<String, ToolOutput>` 原始 JSON 通道）。

## 后果

- 结构化入参的工具从此走 typed 反序列化；单参数标量工具零行为变化、零测试改动。
- 新工具默认应直接用 `JsonNode` / DTO；逐个把老工具改成各自 DTO 不是必须，可机会性推进。
- `ToolExecutor` 在仓库内零外部实现，签名变更影响面收敛在 `ToolRegistry` 内。
