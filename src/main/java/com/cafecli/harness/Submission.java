package com.cafecli.harness;

/** Ordinary submission preparation, before any entry is appended to the agent log. */
public record Submission(boolean blocked, String reason, String prompt) {
    public Submission {
        if (blocked && prompt != null) throw new IllegalArgumentException("blocked submission has no prompt");
        if (!blocked) java.util.Objects.requireNonNull(prompt, "prompt");
    }
    public static Submission proceed(String prompt) { return new Submission(false, null, java.util.Objects.requireNonNull(prompt)); }
    public static Submission block(String reason) { return new Submission(true, reason, null); }
}
