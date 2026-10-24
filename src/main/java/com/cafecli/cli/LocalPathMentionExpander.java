package com.cafecli.cli;

import java.nio.file.Path;

/** Compatibility construction for the v1 input path; expansion lives in the shared neutral leaf. */
final class LocalPathMentionExpander {
    private final com.cafecli.input.LocalPathMentionExpander delegate;
    LocalPathMentionExpander(Path projectRoot) {
        delegate = new com.cafecli.input.LocalPathMentionExpander(projectRoot, Path.of(System.getProperty("user.home")));
    }
    String expand(String input) { return delegate.expand(input); }
}
