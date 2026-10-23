package com.cafecli.tool;

/** The task leaves' injected execution port; implementations own agent construction and lifetime. */
public interface TaskSpawner {
    String spawn(String description, String prompt);
    String spawnBackground(String description, String prompt);
    String continueTask(String taskId, String message);
    String taskOutput(String taskId);
}
