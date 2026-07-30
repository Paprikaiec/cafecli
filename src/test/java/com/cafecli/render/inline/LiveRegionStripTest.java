package com.cafecli.render.inline;

import com.cafecli.hitl.ApprovalRequest;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveRegionStripTest {

    @Test
    void submittedPromptAndApprovalCoalescedInOneFrameBothRemainVisible() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Size size = new Size(80, 30);
        Mockito.when(terminal.getSize()).thenReturn(size);
        Mockito.when(terminal.getBufferSize()).thenReturn(size);
        Mockito.when(terminal.getStringCapability(Mockito.any())).thenReturn("");
        LiveRegionStrip strip = new LiveRegionStrip(terminal);
        ApprovalBlock approval = new ApprovalBlock(terminal, ApprovalRequest.of(
                "write_file", "{\"path\":\"a.txt\"}", "test", "task_2"));

        strip.submitPrompt("submitted prompt\n");
        strip.showApproval(approval);

        String visible = AttributedString.stripAnsi(strip.renderFrame());
        assertTrue(visible.contains("submitted prompt"), visible);
        assertTrue(visible.contains("需要审批"), visible);
        assertTrue(visible.contains("task_2"), visible);
    }
}
