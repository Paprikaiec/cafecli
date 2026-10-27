package com.cafecli.harness;

import com.cafecli.ai.message.StopReason;
import com.cafecli.ai.message.ThinkingBlock;
import com.cafecli.core.AgentEvent;
import com.cafecli.core.RunOutcome;

import java.util.function.Consumer;

/** Per-run headless answer presentation; source messages, model history and compatible traces stay untouched. */
public final class HeadlessRunPresenter implements Consumer<AgentEvent> {
    private final StringBuilder reasoning = new StringBuilder();

    @Override public synchronized void accept(AgentEvent event) {
        if (!AgentSession.MAIN_AGENT_ID.equals(event.agentId())) return;
        if (event instanceof AgentEvent.RunStart) reasoning.setLength(0);
        else if (event instanceof AgentEvent.MessageEnd end
                && end.message().stopReason() != StopReason.ERROR && end.message().stopReason() != StopReason.ABORTED) {
            String text = end.message().content().stream().filter(ThinkingBlock.class::isInstance)
                    .map(ThinkingBlock.class::cast).map(ThinkingBlock::thinking)
                    .collect(java.util.stream.Collectors.joining());
            if (!text.isBlank()) {
                if (!reasoning.isEmpty()) reasoning.append("\n\n");
                reasoning.append(text.trim());
            }
        }
    }

    /** v1 returns accumulated completed-response reasoning only when the run ends naturally. */
    public synchronized String format(RunOutcome outcome) {
        if (!(outcome instanceof RunOutcome.Completed completed)) return RunOutcomeText.format(outcome);
        String answer = completed.message().text().trim();
        String normalizedReasoning = reasoning.toString().trim();
        if (normalizedReasoning.isEmpty()) return answer;
        String transcript = "🧠 思考过程:\n" + normalizedReasoning;
        return answer.isEmpty() ? transcript : transcript + "\n\n" + answer;
    }
}
