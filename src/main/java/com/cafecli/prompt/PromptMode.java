package com.cafecli.prompt;

public enum PromptMode {
    AGENT("modes/agent.md");

    private final String resourcePath;

    PromptMode(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    public String resourcePath() {
        return resourcePath;
    }
}
