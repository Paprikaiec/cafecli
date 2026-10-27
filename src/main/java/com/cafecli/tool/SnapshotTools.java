package com.cafecli.tool;

import com.cafecli.snapshot.RestoreResult;
import com.cafecli.snapshot.SnapshotService;
import com.cafecli.tool.ToolOutput.Status;

import java.util.function.Supplier;

import static com.cafecli.tool.ToolParam.createParameters;

/** Snapshot tool definition contributed by the snapshot capability. */
public final class SnapshotTools {
    private SnapshotTools() { }

    public static Tool revertTurn(Supplier<SnapshotService> snapshotService) {
        return new Tool(
                "revert_turn",
                "恢复到 Side-Git 记录的最近第 N 个 pre-turn 快照。会先记录 pre-restore 快照；属于高危写入操作，必须经 HITL 审批。",
                createParameters(new ToolParam("offset", "integer", "要恢复的 pre-turn 快照序号，1 表示最近一次任务开始前", false)),
                args -> {
                    ToolArgs a = ToolArgs.of(args);
                    int offset = a.intOr("offset", 1);
                    try {
                        RestoreResult result = snapshotService.get().restorePreTurn(Math.max(1, offset));
                        // 恢复没成功时文本仍是 formatForCli() 的那份，只是状态换成 ERROR
                        return result.success()
                                ? ToolOutput.text(result.formatForCli())
                                : ToolOutput.failure(Status.ERROR, result.formatForCli());
                    } catch (Exception e) {
                        return ToolOutput.failure(Status.ERROR, "恢复快照失败: " + e.getMessage());
                    }
                }
        );
    }
}
