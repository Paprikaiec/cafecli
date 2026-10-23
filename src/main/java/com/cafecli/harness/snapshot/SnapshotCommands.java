package com.cafecli.harness.snapshot;

import com.cafecli.snapshot.SnapshotService;

/** v1 snapshot command payload grammar and text, with the final print newline owned by the UI. */
final class SnapshotCommands {
    private final SnapshotService service;

    SnapshotCommands(SnapshotService service) { this.service = service; }

    String snapshot(String payload) {
        String normalized = payload == null || payload.isBlank() ? "list" : payload.trim().toLowerCase();
        if ("status".equals(normalized)) return service.status() + "\n";
        if ("clean".equals(normalized)) return service.clean() + "\n";
        if (!"list".equals(normalized)) return """
                ❌ 未知 /snapshot 子命令: %s
                可用命令：
                  /snapshot
                  /snapshot status
                  /snapshot clean
                  /restore <N>
                """.formatted(payload).trim() + "\n";
        try {
            var snapshots = service.listSnapshots(20);
            if (snapshots.isEmpty()) return "📭 暂无 Side-Git 快照\n";
            StringBuilder out = new StringBuilder("📸 最近 " + snapshots.size() + " 条 Side-Git 快照：\n");
            int index = 0;
            for (var snapshot : snapshots) {
                String hint = "pre-turn".equals(snapshot.phase().label()) ? "  /restore " + ++index : "";
                out.append("   %s %-11s %-18s %s%s%n".formatted(snapshot.shortCommitId(), snapshot.phase().label(),
                        snapshot.turnId(), snapshot.createdAt(), hint));
            }
            return out.toString();
        } catch (Exception e) { return "❌ 读取快照失败: " + e.getMessage() + "\n"; }
    }

    String restore(String payload) {
        int offset = 1;
        if (payload != null && !payload.isBlank()) {
            try { offset = Math.max(1, Math.min(Integer.parseInt(payload.trim()), 100)); }
            catch (NumberFormatException ignored) { }
        }
        try { return service.restorePreTurn(offset).formatForCli() + "\n"; }
        catch (Exception e) { return "❌ 恢复快照失败: " + e.getMessage() + "\n"; }
    }
}
