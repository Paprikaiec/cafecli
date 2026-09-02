package com.cafecli.render.inline.replay;

import org.jline.nativ.CLibrary;
import org.jline.nativ.JLineNativeLoader;

import java.util.function.LongSupplier;

/**
 * Allocation-free TIOCGWINSZ backed by the JNI library already bundled in JLine's jdk11 artifact.
 *
 * <p>Written for the replay recorder and <b>promoted to the renderer's production geometry source</b>
 * (ADR 0010 amendment A3c): it is what makes "geometry is sampled from the kernel" affordable at the
 * millisecond cadence a resize gesture needs, where {@code Terminal.getSize()} would fork
 * {@code stty}. Both callers share one probe rather than growing a second one.
 */
public final class NativeTerminalSizeProbe implements LongSupplier {

    private static final int STDOUT_FILENO = 1;

    private final CLibrary.WinSize windowSize = new CLibrary.WinSize();

    private NativeTerminalSizeProbe() {
    }

    /**
     * The wire format of {@link #getAsLong()}: rows in the high word, columns in the low word, and
     * {@code 0} for "no usable geometry". Held here, with the probe, because it is the probe's
     * contract with every consumer.
     */
    public static long packed(int rows, int columns) {
        if (rows <= 0 || columns <= 0) {
            return 0;
        }
        return ((long) rows << 32) | (columns & 0xffff_ffffL);
    }

    /** Rows out of a {@link #packed(int, int)} sample. */
    public static int rowsOf(long packed) {
        return (int) (packed >>> 32);
    }

    /** Columns out of a {@link #packed(int, int)} sample. */
    public static int columnsOf(long packed) {
        return (int) packed;
    }

    /** Resolve the native library and validate fd 1 once, outside the resize hot path. */
    public static LongSupplier forSystemOutput() {
        try {
            if (!JLineNativeLoader.initialize() || CLibrary.isatty(STDOUT_FILENO) == 0) {
                return null;
            }
            NativeTerminalSizeProbe probe = new NativeTerminalSizeProbe();
            return probe.getAsLong() == 0 ? null : probe;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Override
    public synchronized long getAsLong() {
        // One WinSize instance is reused by the sampler; synchronization also keeps any signal-only
        // fallback from asking JNI to mutate that instance concurrently.
        int result = CLibrary.ioctl(STDOUT_FILENO, CLibrary.TIOCGWINSZ, windowSize);
        if (result != 0) {
            return 0;
        }
        int rows = Short.toUnsignedInt(windowSize.ws_row);
        int columns = Short.toUnsignedInt(windowSize.ws_col);
        return packed(rows, columns);
    }
}
