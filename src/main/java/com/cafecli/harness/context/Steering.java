package com.cafecli.harness.context;

import com.cafecli.core.log.CustomEntry;
import java.util.Objects;

/** User input accepted by an in-flight run; once logged, it remains part of that agent's context. */
public record Steering(String text) implements CustomEntry {
    public static final MessageKind<Steering> KIND = MessageKind.tagged("steering", Steering.class,
            "[用户转向 / 回合中提交]", Steering::text);

    public Steering { Objects.requireNonNull(text, "text"); }
}
