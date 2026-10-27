package com.cafecli.hook;

import java.util.List;

/** Listing presentation shared by legacy and capability-backed sessions. */
public final class HookCommands {
    private HookCommands() {}
    public static String format(List<HookDispatcher.HookListing> listing, java.util.function.Predicate<HookEvent> enabledEvent) {
        if (listing.isEmpty()) {
            return "未加载任何生命周期 Hook（内建租户需 -D 开关，shell 租户配 hooks.json）。\n";
        }
        StringBuilder sb = new StringBuilder("生命周期 Hook（本会话）:\n");
        for (com.cafecli.hook.HookDispatcher.HookListing h : listing) {
            String enabled = enabledEvent.test(h.event()) ? "on" : "off";
            sb.append(String.format("  %-16s %-22s [%-7s] %-3s%s  fires=%d blocks=%d%n",
                    h.event(), h.name(), h.source(), enabled,
                    h.matcher() != null ? " matcher=" + h.matcher() : "",
                    h.fires(), h.blocks()));
        }
        return sb.toString();
    }

}
