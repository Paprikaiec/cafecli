# 03 — compile-gate 内建 Stop hook

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/lifecycle-hook-seam/PRD.md`（ADR 0008）

## What to build

在 issue 02 的 seam 上落第一个真实内建租户 compile-gate：agent 准备收尾时，若**本回合改过文件**，跑评测环境给定的构建命令；编不过则 `block` + 把构建报错作为 `HookDecision.feedback` 注入下一轮、续跑，让 agent 自纠正。直接打 build-break→0/0（floci-88/299/352/grpc）与"从不自检"（floci-164）。

- 触发：本回合至少一次写工具（`write_file`/`edit_file`）成功落盘；无改动则 no-op（纯问答 / 只读回合正常收尾）。改动判定由主循环跟踪工具执行得出。
- 校验命令：由 `run`/eval 经 `-D`（`cafecli.finish.verify.cmd`）注入，编译范围（如 `mvn -q test-compile`）；不做自动探测；命令缺失则 gate 不启用。
- 兜底：最大重阻次数（`-D` 可配、默认小值）封顶；每次 block 消耗一轮，硬轮数始终是终极兜底。
- 模式门控：默认关闭、`run`/eval 开启（沿用 `AgentBudget` 的 `-D` / 系统属性风格）。
- 与既有 per-edit LSP 诊断注入互补：LSP advisory、不阻拦；compile-gate finish-time、block；跑真实构建命令而非复用 LSP（SWE-bench 镜像可能无 LSP server）。

可演示（测试）：脚本化 LLM 写出编不过的代码→收尾被 block、构建报错入下一轮→改对后正常收尾；只读回合正常收尾；重阻达上限后放行。

## Acceptance criteria

- [ ] compile-gate 注册为 issue 02 seam 上的内建 `Stop` hook，provenance=builtin
- [ ] 仅当本回合有写工具成功落盘时触发，否则 no-op
- [ ] 校验命令经 `-Dcafecli.finish.verify.cmd` 注入并执行；缺失时 gate 不启用；不做自动探测
- [ ] 编译失败 → block + 构建报错作为 feedback 注入下一轮、循环续跑
- [ ] 最大重阻次数封顶；硬轮数 / 预算先到时让路
- [ ] 模式门控：默认关、`run`/eval 开
- [ ] trace 可见 compile-gate 触发 / 重阻次数（可归因）
- [ ] 测试（seam）：compile-gate `Hook.handle()` 纯单元，`-Dcafecli.finish.verify.cmd` 指向 `true`/`false`/临时脚本（不跑真 mvn）断言 pass / block / 重阻上限 / 无改动 no-op；`Agent.run()` + 脚本化 `LlmClient` 断言 block→续跑端到端

## Blocked by

Issue 02（Stop 事件 Hook seam）
