package com.cafecli.cli;

import com.cafecli.app.SystemEntry;

/** Cafecli's packaged entry; all execution is composed by the application layer. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        System.exit(SystemEntry.run(args));
    }
}
