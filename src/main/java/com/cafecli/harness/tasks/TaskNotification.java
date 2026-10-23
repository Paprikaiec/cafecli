package com.cafecli.harness.tasks;

import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.log.CustomEntry;
import com.cafecli.harness.context.MessageKind;
import java.util.Objects;
import java.util.Optional;

/** Result-only task completion delivered through its parent's boundary inbox. */
public record TaskNotification(String taskId, String result) implements CustomEntry {
    public static final MessageKind<TaskNotification> KIND = new MessageKind<>("task-notification", TaskNotification.class,
            entry -> Optional.of(UserMessage.text("<task-notification task_id=\"" + entry.taskId()
                    + "\">\n" + entry.result() + "\n</task-notification>")));
    public TaskNotification { Objects.requireNonNull(taskId); Objects.requireNonNull(result); }
}
