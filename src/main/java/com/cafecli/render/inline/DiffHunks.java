package com.cafecli.render.inline;

import java.util.ArrayList;
import java.util.List;

/** Rendering-neutral LCS diff and context-hunk grouping. */
final class DiffHunks {

    private static final int CONTEXT_LINES = 2;

    enum OpType { EQUAL, ADD, DELETE }

    record DiffOp(OpType type, String text, int beforeIndex, int afterIndex) {
    }

    record Hunk(int beforeStart, int beforeCount, int afterStart, int afterCount,
                List<DiffOp> ops) {
        String header() {
            return "@@ -" + (beforeStart + 1) + "," + beforeCount
                    + " +" + (afterStart + 1) + "," + afterCount + " @@";
        }
    }

    private DiffHunks() {
    }

    static List<DiffOp> compute(String[] before, String[] after) {
        int n = before.length;
        int m = after.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = before[i].equals(after[j])
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<DiffOp> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (before[i].equals(after[j])) {
                ops.add(new DiffOp(OpType.EQUAL, before[i], i++, j++));
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add(new DiffOp(OpType.DELETE, before[i], i++, -1));
            } else {
                ops.add(new DiffOp(OpType.ADD, after[j], -1, j++));
            }
        }
        while (i < n) {
            ops.add(new DiffOp(OpType.DELETE, before[i], i++, -1));
        }
        while (j < m) {
            ops.add(new DiffOp(OpType.ADD, after[j], -1, j++));
        }
        return List.copyOf(ops);
    }

    static List<Hunk> group(List<DiffOp> ops) {
        List<Hunk> hunks = new ArrayList<>();
        int index = 0;
        while (index < ops.size()) {
            while (index < ops.size() && ops.get(index).type() == OpType.EQUAL) {
                index++;
            }
            if (index >= ops.size()) {
                break;
            }
            int start = Math.max(0, index - CONTEXT_LINES);
            int end = index;
            int equalRun = 0;
            while (end < ops.size()) {
                DiffOp op = ops.get(end);
                if (op.type() == OpType.EQUAL) {
                    equalRun++;
                    if (equalRun >= 2 * CONTEXT_LINES) {
                        break;
                    }
                } else {
                    equalRun = 0;
                }
                end++;
            }
            int close = Math.min(ops.size(), end + CONTEXT_LINES - equalRun);
            List<DiffOp> hunkOps = List.copyOf(ops.subList(start, close));
            int beforeCount = (int) hunkOps.stream().filter(op -> op.type() != OpType.ADD).count();
            int afterCount = (int) hunkOps.stream().filter(op -> op.type() != OpType.DELETE).count();
            hunks.add(new Hunk(firstBeforeIndex(hunkOps), beforeCount,
                    firstAfterIndex(hunkOps), afterCount, hunkOps));
            index = close;
        }
        return List.copyOf(hunks);
    }

    private static int firstBeforeIndex(List<DiffOp> ops) {
        return ops.stream().filter(op -> op.beforeIndex() >= 0)
                .mapToInt(DiffOp::beforeIndex).findFirst().orElse(0);
    }

    private static int firstAfterIndex(List<DiffOp> ops) {
        return ops.stream().filter(op -> op.afterIndex() >= 0)
                .mapToInt(DiffOp::afterIndex).findFirst().orElse(0);
    }
}
