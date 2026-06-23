package com.paicli.agent.task;

/**
 * 后台任务完成回调：派生器（{@code ForegroundAgentSpawner}）在一个后台任务跑完时调用，把它的
 * result-only 通知投递回事件循环（{@code AgentSession#onBackgroundTaskDone}）。
 *
 * <p>这是 spawn_agent({background:true}) 的「完成 → push」接缝：派生器不反向依赖 AgentSession，
 * 由组合根把 {@code AgentSession::onBackgroundTaskDone} 注入为本回调。
 */
@FunctionalInterface
public interface BackgroundCompletionSink {
    void onTaskDone(String taskId, String notification);
}
