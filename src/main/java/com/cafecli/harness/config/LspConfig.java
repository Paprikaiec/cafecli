package com.cafecli.harness.config;

import java.nio.file.Path;

/** Captured LSP knobs, with the v1 default of enabled and at most twenty diagnostics per request. */
public record LspConfig(Path projectRoot, boolean enabled, int maxDiagnostics) { }
