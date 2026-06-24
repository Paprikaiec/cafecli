# #11 — HITL Pump 设计草案（评审用）

Status: draft-for-review
关联：issue `11-hitl-pump-approval.md`、ADR 0005、`#07` 事件循环 Main 接线（互依，必须同切片落地）

## 0. 为什么需要它（手测实锤）

`InlineApprovalPrompter.prompt()` 用 `terminal.enterRawMode()` + `terminal.reader().read()` **直接读终端**收 y/n。
旧同步 REPL 里回合跑在**主线程**，审批读终端=终端属主线程读，安全。`#07` 把回合搬到**执行器线程**后，审批
在执行器线程读终端，与主线程 `LineReader.readLine()` 抢同一个终端 → 前台脆弱、后台 push 回合（主线程在
readLine 时）**硬死锁**。这就是你 `send_file` 卡死的根因。

**结论**：事件循环要活，HITL 必须改成「读终端只在主线程发生」。

## 1. 目标（issue #11 验收）

1. `requestApproval` 不再在调用线程读 stdin；调用方 **park 在 future**，主线程（泵）渲染+resolve。
2. 前台 + 后台审批共用**一条串行队列**，同一时刻只一个提示，每条**标注请求方**（主 Agent / `task_N`+描述）。
3. 后台任务审批**上浮给用户**（不静默拒绝、不静默 park）。
4. 「全部批准」**会话级全局**（对所有 agent 生效），授权时**明示范围**，措辞按 🔴/🟡 危险等级缩放。
5. 子任务权限 **≤ 派生者**（不可提权）。
6. 测试：双线程 + latch 证明调用方 park 直到决定被投递；断言请求方标注 + 「全部批准」跨 agent 生效。

## 2. 核心：`ApprovalPump`（一个新的 `HitlHandler`）

把「请求」与「裁决」解耦到两个线程：

```
worker 线程 (执行器/虚拟线程)            main 线程 (输入泵 = 终端唯一属主)
─────────────────────────────           ──────────────────────────────────
requestApproval(req)                     drainApprovals():
  if approvedAll(tool/server): 直接放行     for each pending (req, future):
  else:                                       result = renderer.promptApproval(req)  ← 只在主线程读终端
    future = new CompletableFuture            pump.resolve(future, result)
    queue.add((req, future))
    return future.get()  ← park 在此 ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄ resolve 后 unpark，拿到 result
```

接口（实现 `HitlHandler`，**承接现 `RendererHitlHandler` 的 approve-all 粘性**，会话级全局、跨所有 agent）：

```java
public final class ApprovalPump implements HitlHandler {
    private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final Set<String> approvedAllTools   = ConcurrentHashMap.newKeySet();  // 会话级全局
    private final Set<String> approvedAllServers = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled = true;

    public record Pending(ApprovalRequest request, CompletableFuture<ApprovalResult> future) {}

    // —— worker 侧（park）——
    @Override public ApprovalResult requestApproval(ApprovalRequest req) {
        if (!enabled) return ApprovalResult.approve();
        if (isApprovedAllByTool(req.toolName())
            || isApprovedAllByServer(ApprovalPolicy.mcpServerName(req.toolName())))
            return ApprovalResult.approve();                 // 会话级全局放行，对所有 agent
        var future = new CompletableFuture<ApprovalResult>();
        queue.add(new Pending(req, future));
        notifyPending();                                     // 见 §4 后台上浮
        try {
            var r = future.get();                            // ← park
            if (r.isApprovedAllForTool())   approvedAllTools.add(req.toolName());
            if (r.isApprovedAllForServer()) approvedAllServers.add(ApprovalPolicy.mcpServerName(req.toolName()));
            return r;
        } catch (InterruptedException e) { Thread.currentThread().interrupt();
            return ApprovalResult.reject("审批等待被中断（取消）"); }
        catch (ExecutionException e) { return ApprovalResult.reject("审批失败"); }
    }

    // —— main 泵侧（render + resolve）——
    public boolean hasPending() { return !queue.isEmpty(); }
    public Pending poll(Duration t) throws InterruptedException { return queue.poll(t.toMillis(), MILLISECONDS); }
    public void resolve(Pending p, ApprovalResult r) { p.future().complete(r); }

    @Override public boolean isApprovedAllByTool(String t)   { return t != null && approvedAllTools.contains(t); }
    @Override public boolean isApprovedAllByServer(String s) { return s != null && approvedAllServers.contains(s); }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean e) { this.enabled = e; }
    // clearApprovedAll / clearApprovedAllForServer：清 set（/hitl 重置）
}
```

`requestApproval` 已是 `HitlHandler` 契约里的同步阻塞方法——**调用方代码零改动**（`HitlToolRegistry`
照旧调它），只是它内部从「读终端」变成「park 在 future」。这是关键：接缝不动，行为换底。

## 3. 与事件循环（#07 接线）的整合 —— 前台回合（修死锁的主路径）

`#07` 接线后，前台 ReAct 回合跑在执行器线程；主线程提交后不再 dumb `awaitIdle`，而是**泵审批**：

```java
// Main：提交一个前台 ReAct 任务后
agentSession.postEvent(new UserSubmit(taskInput));
while (!agentSession.turnIdle()) {                 // 本轮（含期间 drain 的 push 回合）未跑完
    ApprovalPump.Pending p = approvalPump.poll(Duration.ofMillis(100));
    if (p != null) {
        ApprovalResult r = renderer.promptApproval(p.request());   // ← 主线程读终端，无争用
        approvalPump.resolve(p, r);
    }
}
```

此时主线程**不在 readLine**（它在泵循环里），渲染审批=终端属主线程渲染 → **无死锁**。
这条路径就修掉了你 `send_file` 卡死的场景（前台回合里的危险工具审批）。

## 4. 后台任务审批「上浮」—— 两个方案，请选

后台 push 回合的审批难在：它要审批时，主线程可能**正卡在 `readLine`**（用户在提示符发呆）。
队列里有 pending，但主线程在 readLine 里看不到。两条路：

- **方案 A（推荐，JLine 安全）「告知 + 下次按键处理」**：后台任务入队审批时，`notifyPending()` 经
  `printAbove` 打一行「⏸ task_N 需要你审批 `edit_file`，回车处理」；任务在 future 上 park。主线程的输入泵
  **每次 readLine 返回后先 drain 待审批**（把空回车/任意提交先当「去处理审批」），渲染+resolve 完再决定这行
  是否当用户输入。→ 完全不碰 JLine 内部，无信号、无 buffer 抢救；代价：后台审批需用户敲一下回车才弹出
  （但已 printAbove 告知）。
- **方案 B（即时，但要动 JLine）「信号打断 readLine」**：入队时 `terminal.raise(Signal.INT)` 打断 readLine
  → 抛 `UserInterruptException` → 主循环据「有 pending」标志判定是审批打断（非真 Ctrl+C），渲染+resolve，
  再以保留的 buffer 重进 readLine。→ 即时弹出，但需处理「与真 Ctrl+C 区分 + 半输入 buffer 抢救」，正是
  ready-for-human 的高风险点，需要在真终端反复手测。

**建议**：本切片先上**方案 A**（安全、足够满足「不静默、上浮给用户」），把方案 B 作为后续 UX 增强。

## 5. 请求方标注（每条审批标谁在请求）

复用 `ApprovalRequest.callerContext`（现为 null）。每个执行路径带自己的身份标签：

- 主 Agent 的 `HitlToolRegistry` → `callerContext = "主 Agent"`。
- 每任务的工具表（`#02` 的 child registry）→ `callerContext = "task_N · <description>"`。

落点：`HitlToolRegistry.executeAfterExplicitApproval` 里 `ApprovalRequest.of(name, args, null, callerContext, notice)`
的 `callerContext` 从一个**每 registry 的请求方标签字段**取（`setRequesterLabel(String)`）。`ApprovalRequest.toDisplayText()`
已经会渲染「来源」字段，零改动即可显示。

## 6. 后台任务也走 HITL（不再静默拒绝）

现状：child registry 用静默 renderer **auto-reject** 危险工具。#11 改为：child 的危险工具也路由到**同一个
共享 `ApprovalPump`**（注入），带 task 标签。于是后台任务的危险操作照常上浮给用户。
- 「子任务权限 ≤ 派生者」：所有 agent 共用一个 pump + 一份 `ApprovalPolicy` + 一份 approve-all set，子任务
  拿不到比主 Agent 更宽的放行；approve-all 是会话级，子任务**受益于**（而非**扩张**）已有授权。无独立提权路径。

## 7. 「全部批准」会话级全局 + 范围披露 + 危险分级

- approve-all 粘性存在 `ApprovalPump`（一份），对**所有 agent（主+任务）**生效——满足「会话级全局」。
- 授权时**明示范围**：审批 UI 的「全部批准」选项文案明确「对本会话所有 agent（含正在跑/将来的任务）生效」。
- **危险分级缩放**：沿用 `ApprovalPolicy.getDangerLevel`（🔴 高危 execute_command/revert_turn、🟡 中危
  write/edit/create、🟡 MCP）。等级越高，approve-all 的措辞越显著（如 🔴 工具的 approve-all 加重二次确认/红字）。
  具体文案在渲染层（`InlineApprovalPrompter`），手测打磨。

## 8. 可测接缝（headless，本切片的测试主力）

`ApprovalPumpTest`（双线程 + latch，无终端）：
- **park/resolve**：worker 线程 `requestApproval` → 阻塞；主线程 `poll` 拿到 pending、`resolve(approve())` →
  worker unpark 拿到 approve。latch 证明「resolve 前 worker 确实 park 着」。
- **请求方标注**：构造带 `callerContext="task_3 · 调研"` 的 request，断言 pump 透传、`toDisplayText` 含「来源」。
- **approve-all 跨 agent**：resolve 一个 `approveAll()` → 之后**另一个** worker 对同工具 `requestApproval` **直接放行**
  （不入队）→ 证明会话级全局。
- **enabled=false 直接放行**；**中断 → reject(取消)**。

JLine 渲染（`renderer.promptApproval` 在主线程）、方案 A 的 printAbove 告知 + readLine-后-drain：留在 `Main`、
**只手测**。

## 9. 本切片范围

**做**：`ApprovalPump`（park/resolve + 会话级 approve-all）、`HitlToolRegistry` 加请求方标签、child registry
路由到共享 pump（带标签）、Main 输入泵改为「前台泵审批 + readLine-后-drain」、approve-all 文案披露范围 + 危险分级、
`ApprovalPumpTest`。**与 `#07` Main 接线同切片落地**（前台回合上执行器是审批 pump 的前提）。

**顺带解锁**：`#07` 重接线在安全审批下回归；`#09` 集成（ESC 绑定→cancelForegroundAndAttached、/task cancel
线程中断、退役 CancellationContext 继承）可在同一接线里挂上。

**本切片不做**：方案 B 信号打断（后续 UX）、`#08` auto-bg、`#10` /task 重指向、`#13` 快照锚定（后续切片）。

## 10. 待你拍板的决策

1. **后台审批**：方案 A（告知+回车处理，安全）还是方案 B（信号即时打断，需重手测）？（建议 A）
2. **本切片是否连带把 `#07` Main 接线重做 + `#09` ESC 集成一起挂上**？（它们与 pump 互依；建议一起，做完一次手测就能验前台审批 + ESC + 后台 push 全链）
3. **approve-all 危险分级**：🔴 工具的「全部批准」是否要加二次确认（防手滑会话级放行 execute_command）？（建议要）
4. 默认值确认：审批 pump 队列无上限（串行消费即可）；`/hitl off` 时 pump 全放行（沿用现 enabled 语义）。

## 11. 对标 Claude Code（`claude-code/src` 上游源码）—— 设计已验证

CC 的工具权限流与本设计**结构一致**，逐条印证：

- `CanUseToolFn` 是 `async (tool, input, …) => Promise<PermissionDecision>`，工具 **await 这个 Promise**，UI 在
  用户裁决后 resolve（`useCanUseTool.tsx` `new Promise(resolve => …)`）。= 本设计 worker **park 在 future**、主线程 resolve。
- `hasPermissionsToUseTool` 先查已有规则，命中即 `behavior:'allow'` **不弹窗**。= 本设计 approve-all 短路。
- `handleCoordinatorPermission` 把弹窗**串行化**（一次一个），并发请求排队。= 本设计串行队列。
- **`ctx.resolveIfAborted(resolve)` 在权限流每一步都查**：一旦 abort（ESC/Ctrl+C），**正 park 的权限 Promise 直接 resolve 成取消**
  （`cancelAndAbort`）。→ **要点（并入本切片）**：`ApprovalPump.requestApproval` 在 park 期间必须**可被 ESC 取消**——
  ESC（`cancelForegroundAndAttached`）应把当前回合/附着任务的 pending 审批 **reject 掉**，让 worker unpark 收尾。这正是 #11↔#09 的耦合点。
- `behavior` ∈ `allow|deny|ask` + `updatedInput`（改参数后放行）。= 本设计 APPROVED/REJECTED/MODIFIED。
- `forceDecision`：预判路径（headless / 规则已定）。= 本设计 `enabled=false` 直接放行 / headless 自动放行。

**结论**：`ApprovalPump` 是 CC 这套（tool await promise ↔ UI resolve ↔ 规则短路 ↔ 串行 ↔ abort 取消）的 Java/JLine 对等实现。
新增并入：pump 暴露 `rejectPending(predicate, reason)` 供 ESC 取消正 park 的审批。
</content>
