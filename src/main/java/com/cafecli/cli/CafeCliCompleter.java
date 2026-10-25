package com.cafecli.cli;

import com.cafecli.mcp.resources.McpResourceDescriptor;
import com.cafecli.skill.Skill;
import org.jline.reader.*;
import java.util.List;
import java.util.function.Supplier;

/** Legacy entry point over the shared completion leaf. */
final class CafeCliCompleter implements Completer {
    private final Completer delegate;
    CafeCliCompleter(Supplier<List<McpResourceDescriptor>> resources) { this(resources,List::of); }
    CafeCliCompleter(Supplier<List<McpResourceDescriptor>> resources, Supplier<List<Skill>> skills) {
        delegate = new com.cafecli.input.CommandCompleter(resources,skills,com.cafecli.input.CommandHints::all);
    }
    @Override public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        delegate.complete(reader,line,candidates);
    }
}
