package com.cafecli.render.inline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cafecli.hitl.ApprovalPolicy;
import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Main/input-thread reader for the inline approval flow; it never writes terminal bytes. */
public final class InlineApprovalPrompter {

    @FunctionalInterface
    interface Presenter {
        void show(String notice, String prompt);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ATTEMPTS = 5;

    private final Terminal terminal;
    private final BufferedReader stdinReader;
    private final Presenter presenter;
    private String notice = "";

    InlineApprovalPrompter(Terminal terminal, Presenter presenter) {
        this(terminal,
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                presenter);
    }

    InlineApprovalPrompter(Terminal terminal, BufferedReader stdinReader, Presenter presenter) {
        this.terminal = terminal;
        this.stdinReader = stdinReader;
        this.presenter = presenter == null ? (message, prompt) -> { } : presenter;
    }

    public ApprovalResult prompt(ApprovalRequest request) {
        boolean sensitive = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            show(ApprovalBlock.options(request));
            int key = readSingleKey();
            if (key < 0) {
                return ApprovalResult.reject("无法读取按键");
            }
            char ch = Character.toLowerCase((char) key);
            switch (ch) {
                case 'y', '\n', '\r' -> {
                    return ApprovalResult.approve();
                }
                case 'a' -> {
                    if (sensitive) {
                        notice = "Sensitive operations cannot be approved globally; choose y/n/s/m";
                        continue;
                    }
                    return promptApproveAllScope(request);
                }
                case 'n' -> {
                    return ApprovalResult.reject(promptForReason());
                }
                case 's' -> {
                    return ApprovalResult.skip();
                }
                case 'm' -> {
                    ApprovalResult modified = promptForModifiedArgs(request);
                    if (modified != null) {
                        return modified;
                    }
                }
                default -> notice = "Unrecognized key '" + ch + "'; choose y/a/n/s/m";
            }
        }
        return ApprovalResult.reject("连续多次无效输入");
    }

    private void show(String prompt) {
        presenter.show(notice, prompt);
    }

    private int readSingleKey() {
        Attributes original;
        try {
            original = terminal.enterRawMode();
        } catch (Exception e) {
            return -1;
        }
        try {
            terminal.flush();
            return terminal.reader().read();
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                terminal.setAttributes(original);
            } catch (Exception ignored) {
            }
        }
    }

    private String promptForReason() {
        notice = "Rejection reason (press Enter to skip)";
        show("type reason and press Enter");
        try {
            String line = stdinReader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            return "";
        }
    }

    private ApprovalResult promptApproveAllScope(ApprovalRequest request) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());
        if (mcpServer == null || mcpServer.isBlank()) {
            return ApprovalResult.approveAll();
        }
        notice = "Approve all scope: [tool/Enter] this tool, [server] MCP server " + mcpServer;
        show("type scope and press Enter");
        String scope;
        try {
            scope = stdinReader.readLine();
        } catch (IOException e) {
            scope = "";
        }
        String normalized = scope == null ? "" : scope.trim().toLowerCase();
        return "server".equals(normalized) || "s".equals(normalized)
                ? ApprovalResult.approveAllByServer()
                : ApprovalResult.approveAll();
    }

    private ApprovalResult promptForModifiedArgs(ApprovalRequest request) {
        notice = "Current arguments: " + request.arguments();
        show("type replacement JSON (blank keeps original) and press Enter");
        String modified;
        try {
            modified = stdinReader.readLine();
        } catch (IOException e) {
            notice = "Unable to read replacement arguments";
            return null;
        }
        if (modified == null || modified.isBlank()) {
            return ApprovalResult.approve();
        }
        String trimmed = modified.trim();
        try {
            JSON.readTree(trimmed);
        } catch (Exception e) {
            notice = "Invalid JSON: " + e.getMessage();
            return null;
        }
        return ApprovalResult.modify(trimmed);
    }
}
