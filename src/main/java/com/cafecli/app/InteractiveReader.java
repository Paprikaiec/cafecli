package com.cafecli.app;

import com.cafecli.harness.config.HistoryConfig;
import com.cafecli.render.inline.CafeCliLineReader;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;

import java.util.Map;

/** The production reader construction boundary shared by terminal startup and reader contract rigs. */
public final class InteractiveReader {
    private InteractiveReader() {}

    public static LineReader create(Terminal terminal, boolean capture, HistoryConfig history) {
        var variables = new java.util.HashMap<String,Object>();
        variables.put(LineReader.AMBIGUOUS_BINDING,100L);
        var safeHistory = new com.cafecli.input.InputHistory();
        var highlighter = new com.cafecli.input.InputHighlighter();
        var reader = capture ? CafeCliLineReader.create(terminal,"cafecli",variables,safeHistory,null,highlighter)
                : LineReaderBuilder.builder().terminal(terminal).variables(variables).history(safeHistory)
                        .highlighter(highlighter).build();
        reader.option(LineReader.Option.BRACKETED_PASTE,true);
        reader.option(LineReader.Option.AUTO_LIST,true);
        reader.option(LineReader.Option.AUTO_MENU,true);
        new org.jline.widget.AutopairWidgets(reader).enable();
        try {
            java.nio.file.Files.createDirectories(history.file().getParent());
            reader.setVariable(LineReader.HISTORY_FILE,history.file());
            reader.setVariable(LineReader.HISTORY_SIZE,history.size());
            reader.setVariable(LineReader.HISTORY_FILE_SIZE,history.fileSize());
            reader.setOpt(LineReader.Option.HISTORY_IGNORE_SPACE);
            reader.setOpt(LineReader.Option.HISTORY_IGNORE_DUPS);
            reader.setOpt(LineReader.Option.HISTORY_REDUCE_BLANKS);
            reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
            reader.getHistory().load();
        } catch (java.io.IOException ignored) {
            // Match the legacy entry: unavailable history storage never prevents a session.
        }
        return reader;
    }
}
