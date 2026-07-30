package com.cafecli.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #04 验收：主 Agent（ReAct / AGENT 模式）的已组装系统提示包含子任务派生纪律——
 * continue-vs-spawn 选择、绝不下放理解、任务通知是需转述给真实用户的内部信号。
 */
class SpawnPromptDisciplineTest {

    private String assembledAgentPrompt() {
        return PromptAssembler.createDefault().assemble(PromptMode.AGENT, PromptContext.empty());
    }

    @Test
    void agentPromptContainsContinueVsSpawnGuidance() {
        String prompt = assembledAgentPrompt();
        assertTrue(prompt.contains("spawn_agent") && prompt.contains("send_message"),
                "主 Agent 提示应介绍 spawn_agent / send_message 派生与续派工具");
        assertTrue(prompt.contains("续派") && prompt.contains("重开"),
                "应包含 continue-vs-spawn（续派 vs 重开）的选择指引");
    }

    @Test
    void agentPromptContainsNeverDelegateUnderstandingRule() {
        String prompt = assembledAgentPrompt();
        assertTrue(prompt.contains("绝不下放理解"),
                "应包含「绝不下放理解」规则");
        assertTrue(prompt.contains("文件路径/行号"),
                "应要求把发现综合成带文件路径/行号的具体指令，而非「基于你的发现」");
    }

    @Test
    void agentPromptFramesTaskNotificationAsInternalSignalToRelay() {
        String prompt = assembledAgentPrompt();
        assertTrue(prompt.contains("不是真实用户") || prompt.contains("不是用户"),
                "应说明任务通知是「像用户但非用户」的信号");
        assertTrue(prompt.contains("汇报给真实用户"),
                "应要求把任务结论转述/汇报给真实用户，而非感谢任务");
        assertTrue(prompt.contains("task_output"),
                "应提示需要细节时用 task_output 拉取完整轨迹");
    }
}
