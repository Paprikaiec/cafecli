package com.cafecli.harness.skills;

import com.cafecli.core.log.CustomEntry;
import com.cafecli.harness.context.MessageKind;

/** The one-shot body prefix consumed at the start of the next real user run. */
public record SkillBody(String prefix) implements CustomEntry {
    public static final MessageKind<SkillBody> KIND =
            MessageKind.userPrefix("skill-body", SkillBody.class, SkillBody::prefix);
}
