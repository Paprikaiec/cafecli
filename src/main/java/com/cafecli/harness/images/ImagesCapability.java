package com.cafecli.harness.images;

import com.cafecli.ai.message.ImageBlock;
import com.cafecli.ai.message.TextBlock;
import com.cafecli.ai.message.ToolResultMessage;
import com.cafecli.ai.message.UserContent;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.RunInput;
import com.cafecli.core.log.LogEntry;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.core.log.UserEntry;
import com.cafecli.harness.AgentScope;
import com.cafecli.harness.Capability;
import com.cafecli.harness.context.RunInputEntry;
import com.cafecli.image.ImageReferenceParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Image attachment presentation; original payloads remain in the append-only source log. */
public final class ImagesCapability implements Capability {
    private final Path projectRoot;

    public ImagesCapability(Path projectRoot) { this.projectRoot = Objects.requireNonNull(projectRoot); }

    @Override public void attachAgent(AgentScope agent) {
        agent.inputPreparation((input, cancellation) -> {
            List<UserContent> source = new ArrayList<>(input.source().content());
            var proposed = agent.presentInput(input);
            String text = proposed.presentation().content().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                    .map(TextBlock::text).collect(Collectors.joining());
            String userText = input.source().content().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                    .map(TextBlock::text).collect(Collectors.joining());
            int prefixLength = text.length() - userText.length();
            var resolved = ImageReferenceParser.parseWithOrigins(text, projectRoot);
            var parsed = resolved.input();
            if (parsed.text().equals(text) && parsed.images().isEmpty()) return input;
            List<UserContent> presentation = new ArrayList<>();
            presentation.add(new TextBlock(parsed.text()));
            proposed.presentation().content().stream().filter(block -> !(block instanceof TextBlock))
                    .forEach(presentation::add);
            for (int i = 0; i < parsed.images().size(); i++) {
                var image = parsed.images().get(i);
                var block = new ImageBlock(image.base64(), image.mimeType());
                if (resolved.imageOffsets().get(i) >= prefixLength) source.add(block);
                presentation.add(block);
            }
            return new RunInput(new UserMessage(source), new UserMessage(presentation),
                    input.entries(), proposed.preparedPrefixCount());
        });
        agent.projectionStage(ImagesCapability::present);
    }

    private static List<LogEntry> present(List<LogEntry> entries) {
        List<LogEntry> view = new ArrayList<>();
        List<UserEntry> attachments = new ArrayList<>();
        int latestUser = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof UserEntry
                    || entries.get(i) instanceof RunInputEntry input && !input.sourceUser()) latestUser = i;
        }
        for (int i = 0; i < entries.size(); i++) {
            LogEntry entry = entries.get(i);
            boolean historical = i < latestUser;
            if (!(entry instanceof ToolResultEntry) && !attachments.isEmpty()) {
                view.addAll(attachments);
                attachments.clear();
            }
            if (entry instanceof ToolResultEntry result) {
                var message = result.message();
                List<UserContent> images = message.content().stream().filter(ImageBlock.class::isInstance).toList();
                if (!images.isEmpty()) {
                    view.add(new ToolResultEntry(new ToolResultMessage(message.toolCallId(), message.toolName(),
                            message.content().stream().filter(block -> !(block instanceof ImageBlock)).toList(),
                            message.isError()), result.status(), result.details()));
                    List<UserContent> content = new ArrayList<>();
                    content.add(new TextBlock("工具 " + message.toolName() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
                    content.addAll(images);
                    attachments.add(new UserEntry(historical ? withoutImages(new UserMessage(content)) : new UserMessage(content)));
                    continue;
                }
            }
            if (historical && entry instanceof RunInputEntry input && input.presentation() != null) {
                view.add(new RunInputEntry(input.sources(), input.source(),
                        withoutImages(input.presentation()), input.metadata(), input.sourceUser()));
            } else if (historical && entry instanceof UserEntry user) {
                view.add(new UserEntry(withoutImages(user.message()),
                        user.presentation() == null ? null : withoutImages(user.presentation()), user.preparedPrefixCount()));
            } else view.add(entry);
        }
        view.addAll(attachments);
        return List.copyOf(view);
    }

    private static UserMessage withoutImages(UserMessage message) {
        long count = message.content().stream().filter(ImageBlock.class::isInstance).count();
        if (count == 0) return message;
        List<UserContent> content = new ArrayList<>(message.content().stream()
                .filter(block -> !(block instanceof ImageBlock)).toList());
        content.add(new TextBlock("[历史图片附件已省略 " + count
                + " 张；如需重新查看，请使用上文 Image source 或相关工具结果。]"));
        return new UserMessage(content);
    }
}
