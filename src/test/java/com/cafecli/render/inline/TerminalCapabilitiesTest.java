package com.cafecli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalCapabilitiesTest {

    private static final String GRAPHEME_CLUSTER_PROP = org.jline.terminal.TerminalBuilder.PROP_GRAPHEME_CLUSTER;

    private String savedSysProp;
    private String savedSynchronizedOutput;
    private String savedOsc8;
    private String savedGraphemeCluster;

    @BeforeEach
    void save() {
        savedSysProp = System.getProperty("paicli.no.statusbar");
        savedSynchronizedOutput = System.getProperty("paicli.render.synchronized-output");
        savedOsc8 = System.getProperty("paicli.render.osc8");
        savedGraphemeCluster = System.getProperty(GRAPHEME_CLUSTER_PROP);
    }

    @AfterEach
    void restore() {
        if (savedSysProp == null) {
            System.clearProperty("paicli.no.statusbar");
        } else {
            System.setProperty("paicli.no.statusbar", savedSysProp);
        }
        if (savedSynchronizedOutput == null) {
            System.clearProperty("paicli.render.synchronized-output");
        } else {
            System.setProperty("paicli.render.synchronized-output", savedSynchronizedOutput);
        }
        if (savedOsc8 == null) {
            System.clearProperty("paicli.render.osc8");
        } else {
            System.setProperty("paicli.render.osc8", savedOsc8);
        }
        if (savedGraphemeCluster == null) {
            System.clearProperty(GRAPHEME_CLUSTER_PROP);
        } else {
            System.setProperty(GRAPHEME_CLUSTER_PROP, savedGraphemeCluster);
        }
    }

    @Test
    void nullTerminalIsNotAnsiCapable() {
        assertFalse(TerminalCapabilities.supportsAnsi(null));
    }

    @Test
    void dumbTerminalIsNotAnsiCapable() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");
        assertFalse(TerminalCapabilities.supportsAnsi(terminal));
    }

    @Test
    void xtermTerminalIsAnsiCapable() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        assertTrue(TerminalCapabilities.supportsAnsi(terminal));
    }

    @Test
    void scrollRegionRequiresMinimumSize() {
        Terminal small = Mockito.mock(Terminal.class);
        Mockito.when(small.getType()).thenReturn("xterm-256color");
        Mockito.when(small.getSize()).thenReturn(new Size(40, 4));
        assertFalse(TerminalCapabilities.supportsScrollRegion(small));
    }

    @Test
    void scrollRegionTrueOnNormalTerminal() {
        Terminal normal = Mockito.mock(Terminal.class);
        Mockito.when(normal.getType()).thenReturn("xterm-256color");
        Mockito.when(normal.getSize()).thenReturn(new Size(120, 40));
        assertTrue(TerminalCapabilities.supportsScrollRegion(normal));
    }

    @Test
    void noStatusbarPropertyDisablesScrollRegion() {
        System.setProperty("paicli.no.statusbar", "true");
        Terminal normal = Mockito.mock(Terminal.class);
        Mockito.when(normal.getType()).thenReturn("xterm-256color");
        Mockito.when(normal.getSize()).thenReturn(new Size(120, 40));
        assertFalse(TerminalCapabilities.supportsScrollRegion(normal));
    }

    @Test
    void safeSizeFallbackOnNullSize() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(null);
        Size size = TerminalCapabilities.safeSize(terminal);
        assertEquals(80, size.getColumns());
        assertEquals(24, size.getRows());
    }

    @Test
    void safeSizeFallbackOnZeroSize() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(0, 0));
        Size size = TerminalCapabilities.safeSize(terminal);
        assertEquals(80, size.getColumns());
        assertEquals(24, size.getRows());
    }

    @Test
    void synchronizedOutputPropertyOverridesTerminalDetection() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        System.setProperty("paicli.render.synchronized-output", "true");
        assertTrue(TerminalCapabilities.supportsSynchronizedOutput(terminal));

        System.setProperty("paicli.render.synchronized-output", "false");
        assertFalse(TerminalCapabilities.supportsSynchronizedOutput(terminal));
    }

    @Test
    void graphemeClusterProbeIsDisabledByDefaultSoAppleTerminalDoesNotEchoAStrayP() {
        System.clearProperty(GRAPHEME_CLUSTER_PROP);

        assertFalse(TerminalCapabilities.graphemeClusterProbeAllowed());
    }

    @Test
    void graphemeClusterProbeCanBeReenabledByExplicitJlineProperty() {
        System.setProperty(GRAPHEME_CLUSTER_PROP, "true");

        assertTrue(TerminalCapabilities.graphemeClusterProbeAllowed());
    }

    @Test
    void osc8CapabilityCanBeExplicitlyEnabledOrDisabled() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");

        System.setProperty("paicli.render.osc8", "true");
        assertTrue(TerminalCapabilities.supportsOsc8(terminal));

        System.setProperty("paicli.render.osc8", "false");
        assertFalse(TerminalCapabilities.supportsOsc8(terminal));
    }
}
