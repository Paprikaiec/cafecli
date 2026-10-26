package com.cafecli.harness.config;

import java.nio.file.Path;

/** Captured interactive history storage and limits. */
public record HistoryConfig(Path file, int size, int fileSize) {}
