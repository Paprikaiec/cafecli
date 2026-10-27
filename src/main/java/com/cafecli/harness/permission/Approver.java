package com.cafecli.harness.permission;

import com.cafecli.ai.CancellationToken;

/**
 * Blocking approval port. Implementations must release a pending request when its token is cancelled; they must not
 * read a terminal from the loop thread. PermissionCapability serializes access across all agents of a session.
 */
@FunctionalInterface
public interface Approver {
    ApprovalResult approve(ApprovalRequest request, CancellationToken cancellation) throws InterruptedException;
}
