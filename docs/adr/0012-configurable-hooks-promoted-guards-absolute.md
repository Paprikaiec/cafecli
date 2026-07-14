# 可配置 Hook 子系统提前落地；决策词表四 outcome；守卫对 hook 绝对

> 状态：**accepted**（2026-07-10 用户确认三个关键点：四 outcome 词表、超时 fail-open、hook 命令不过 CommandGuard）。取代 [ADR 0008](0008-lifecycle-hooks-internal-seam-first.md) 的 sequencing 部分；0008 的「契约贵、租户便宜」与「permissions-vs-hooks 分层」两条结论保留。

ADR 0008 定的顺序是「内部 seam 先行、可配置 shell-hook 延后第23期」，其首租户 compile-gate 已被 Phase 0 证据否决（[再基线报告](../../eval/swebench-live/reports/2026-06-26-phase0-editfile-rebaseline.md)：6 个 0/0 无一是生产源编译破坏）。本决定把第23期的**用户可配置生命周期 Hook 子系统**提前，`HookDispatcher` seam 作为其一部分一起交付，首租户换成两个有 eval 证据的内建租户 + `ShellHook` 通道。

## 决策

1. **决策词表**：`CONTINUE` / `BLOCK(reason)` / `ALLOW` / `ASK` 四个 outcome。单一 BLOCK verb——不搬 Claude Code 的 legacy 双通道（`decision: block` + `permissionDecision: deny`），质量阻断与策略阻断走同一 verb，原因一律回灌模型。多 hook 组合取最严：任一 BLOCK 短路；ASK 压过 ALLOW。
2. **守卫对 hook 绝对（有意偏离 Claude Code）**：hook 的 ALLOW 仅免除 HITL 审批，永不豁免 PathGuard/CommandGuard（CC 的 allow 绕过更多权限层）。既有不变量「用户无法批准策略拒绝的请求」延伸为「hook 也无法批准」。
3. **信任模型**：hook 命令本身不过 CommandGuard、不逐次 HITL——守卫防的是模型生成的动作，hook 是用户签署的配置（同 `.git/hooks` 的信任级别）。项目级 hook 配置是供应链风险（clone 恶意仓库即执行任意命令），首次加载走 HITL 确认、按配置内容指纹（SHA-256）钉在用户级信任文件，内容变更即重新确认；拒绝则跳过项目级 hook、会话继续。每次 hook 执行记 AuditLog（事件、命令、exit、决策、耗时）。
4. **Wave 1 事件与租户**：接 `PreToolUse` / `PostToolUse` / `Stop` / `UserPromptSubmit` 四事件（SubagentStart/Stop 延后，payload 已带 taskId、纯增量）。内建首租户 = **读前写台账**（PreToolUse 阻断盲写/陈旧写 + PostToolUse 记账）+ **收尾门**（Stop：有写入而写后无验证命令则阻断收尾）。证据：2026-06-08 A/B「PaiCLI 几乎从不跑测试（jackson/164/248 均 0 次）vs Claude 每实例 3–8 次」；jackson-5837 轨迹「凭假设交付、假设错了没被拦」。收尾门的验证检测只看 agent 自行运行的工作区命令（测试/构建命令模式匹配，可配置），**禁止引用评分测试集**（抗作弊：门不得按实例参数化）。两租户默认关、`-D` 开启（沿 safe-edit 先例），eval 出证据后再翻默认。
5. **Shell 契约对齐 CC**：exit 0 = CONTINUE（stdout 可带 JSON 决策超集）、exit 2 = BLOCK（stderr 即 reason）、其他 exit = 仅告知用户不阻断。契约 schema 预留 `updatedInput` / `updatedToolOutput` / async（wave 2），dispatcher 对未实现字段警告并忽略。
6. **线程与失败语义**：hook 同步跑在调用方 agent 的线程（主串行执行器 / 任务虚拟线程），绝不读终端；信任确认走 ApprovalPump。超时/异常 **fail-open**（视为 CONTINUE + 用户可见警告），阻断型质量 hook 不因挂起卡死 agent；hook 进程挂到当前回合取消令牌，ESC 即销毁。

## 被否决的方案

- **继续 0008 的 seam-first 顺序**：seam 的原首租户 compile-gate 已无证据支撑，为空租户建 seam 失去演练价值；而两个新租户 + 配置通道正好构成「契约 + 内建租户 + 外部租户」的完整闭环。
- **read-before-write 做进 `edit_file` 工具内（CC 的做法）**：否决——作为 PreToolUse 租户落地可以吃自己的狗粮（builtin 与 shell 同契约的第一个证明），且台账天然按 Agent 作用域（每任务 ToolRegistry，ADR 0007）。
- **hook 命令过 CommandGuard / 逐次 HITL**：否决——会拦坏合法 hook（清理脚本、构建命令），且混淆威胁模型；信任前移到配置加载时一次性解决。

## 后果

- CONTEXT.md「生命周期 Hook / 策略守卫」词条已改述：「不做策略拒绝」→「BLOCK 是模型反馈级阻断，不是安全拒绝」；新增词条：Hook 租户 / 项目 Hook 信任 / 读前写台账 / 收尾门。
- `.scratch/lifecycle-hook-seam/` 旧 PRD 的 Solution 作废（eval 驱动、compile-gate 为主），Testing Decisions 五条缝先例保留。
- 收尾门与硬轮数上限天然互为兜底：阻断消耗轮数，不会绕过 `paicli.react.hard.max.iterations`。
- 配置放 `~/.paicli/hooks.json` + 项目 `.paicli/hooks.json`，合并与 `${VAR}` 展开沿用 mcp.json 机制；matcher 为工具名正则。

## 证据回填（2026-07-14，configurable-hooks #08 eval）

决策点 4 引用的证据是 2026-06-08 A/B 的**定性**论断（「PaiCLI 几乎从不跑测试」）。#08 在 SWE-bench-Live Phase 0 band（6 条 core-fail，抗污染）上做了**量化 A/B**（同一 jar、唯一变量 = 5 个 hook `-D` 开关，deepseek-v4-pro，官方 harness），完整报告见 [../../eval/swebench-live/reports/2026-07-14-hooks-ab-builtin-tenants.md](../../eval/swebench-live/reports/2026-07-14-hooks-ab-builtin-tenants.md)。关键数字：

- **解决率：对照 3/6 → 开租户 3/6（不变）。** 全 band 仅 1 次 hook 阻断（grpc-12735 收尾门），读前写台账 0 阻断。
- **「收尾不测试」量化（instance-agnostic 检测器，正则逐字移植自 `FinishGateTenant`，从不引用 gold/F2P/评分测试集）**：紧配置 noedit(50 轮·无 edit_file) **1/6**、Phase 0 对照 **0/6**、Phase 0+租户 **0/6**。即 2026-06-08 的「不测试」是**紧配置 + 旧 jar 的产物**；当前 agent 跨配置已基本验证驱动（0–1/6）。
- **收尾门真实兜底 1 次**（grpc 处理臂：agent 收尾前留了一次未验证的编辑 → 被拦 → 补跑验证），且对照臂本会自测的 6 条**零误伤**。
- **默认值决定：本 band 证据不支持翻默认 —— 两租户维持默认关。** 在已验证驱动的 agent 上它们无解决率增益，只有「可移植的确定性保证」价值（预算收紧 / 编辑模式回退 / 未来模型回归时兜底）。翻默认需要「失败形状仍普遍」的 band 或「收尾门把 fail 转 resolve」的正例，本期均不成立。本 band 的解决率杠杆是 safe-edit（禁写 `src/test/**`），非本两租户 —— 与 Phase 0 的 go/no-go 结论一致。
