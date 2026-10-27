package com.cafecli.app;

import com.cafecli.config.ConfigHome;
import com.cafecli.harness.config.ConfigSources;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The system boundary behind the packaged Cafecli entry.
 *
 * <p>The one class in v2 that reads the JVM's system properties, environment, {@code user.home} and {@code user.dir}
 * (the architecture tests hold it to that): it reads them once, into {@link ConfigSources}, and hands them with the
 * process's own streams to {@link Cli}. Everything past this point gets its inputs as parameters.
 *
 * <p>First it installs v1's property bridge ({@code ConfigHome.installPropertyAliases}) on the process's properties,
 * so reused leaves read their knobs from the system properties under the legacy
 * {@code paicli.*} names only (e.g. {@code PathGuard} reads {@code paicli.safe_edit.enabled}), so without it a
 * {@code -Dcafecli.*} flag would stop reaching them under v2. v2's own loader does not need it — it applies the same
 * rule to the map it is handed.
 */
public final class SystemEntry {

    private SystemEntry() {
    }

    /** Runs the v2 command line on this process; returns the exit code. */
    public static int run(String[] args) {
        return run(args, System.getProperties(), System.getenv(), System.out, System.err);
    }

    /**
     * {@link #run(String[])} on the given process inputs.
     *
     * @param systemProperties bridged in place: for the real process, the live system properties the leaves read
     */
    static int run(String[] args, Properties systemProperties, Map<String, String> environment, PrintStream out,
                   PrintStream err) {
        ConfigHome.installPropertyAliases(systemProperties);
        Map<String, String> properties = new HashMap<>();
        for (String name : systemProperties.stringPropertyNames()) {
            properties.put(name, systemProperties.getProperty(name));
        }
        ConfigSources sources = new ConfigSources(properties, environment,
                Path.of(systemProperties.getProperty("user.home")), Path.of(systemProperties.getProperty("user.dir")));
        return Cli.run(args, sources, out, err);
    }
}
