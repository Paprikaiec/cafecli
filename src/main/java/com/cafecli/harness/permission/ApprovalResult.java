package com.cafecli.harness.permission;

import java.util.Objects;

/** Approval replies retain v1's per-tool, per-server, edit, refusal and skip semantics. */
public record ApprovalResult(Decision decision, String modifiedArguments, String reason) {
    public enum Decision { APPROVED, APPROVED_ALL, APPROVED_ALL_BY_SERVER, REJECTED, MODIFIED, SKIPPED }
    public ApprovalResult { Objects.requireNonNull(decision, "decision"); }
    public static ApprovalResult approve() { return new ApprovalResult(Decision.APPROVED, null, null); }
    public static ApprovalResult approveAll() { return new ApprovalResult(Decision.APPROVED_ALL, null, null); }
    public static ApprovalResult approveAllByServer() { return new ApprovalResult(Decision.APPROVED_ALL_BY_SERVER, null, null); }
    public static ApprovalResult reject(String reason) { return new ApprovalResult(Decision.REJECTED, null, reason); }
    public static ApprovalResult modify(String arguments) { return new ApprovalResult(Decision.MODIFIED, arguments, null); }
    public static ApprovalResult skip() { return new ApprovalResult(Decision.SKIPPED, null, null); }
    public String effectiveArguments(String original) {
        return decision == Decision.MODIFIED && modifiedArguments != null && !modifiedArguments.isBlank()
                ? modifiedArguments : original;
    }
}
