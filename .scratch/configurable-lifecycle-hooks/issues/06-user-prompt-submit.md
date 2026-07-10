# 06: UserPromptSubmit event

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/configurable-lifecycle-hooks/PRD.md` (ADR 0012)

## What to build

Wire the fourth wave-1 event: `UserPromptSubmit` dispatches in the CLI main path after mention/`@path` expansion and before the agent run.

A BLOCK stops the prompt from reaching the agent and shows the reason to the **user** (not the model) — e.g. a hook that catches an accidental secret paste. `additionalContext` is appended to the submitted prompt before the run — e.g. a hook that injects team conventions. The event fires for normal task submissions, not for slash commands; in headless `run` mode the file-supplied task counts as the submitted prompt and fires the event once (a BLOCK there fails the run with the reason on the trace).

No builtin tenant for this event — it exists for shell tenants; the demo is a user-level `hooks.json` entry.

## Acceptance criteria

- [ ] Dispatch happens after mention/path expansion, before the agent run; slash commands do not fire it
- [ ] BLOCK: agent never runs, reason rendered to the user, audit entry written
- [ ] `additionalContext`: appended to the prompt the agent actually receives (assert via scripted-client request history)
- [ ] Headless `run`: event fires once for the task input; BLOCK fails the run with the reason recorded
- [ ] Demo: user-level hook blocks a prompt matching a secret pattern; another injects a context line
- [ ] Tests at the existing main-path input seam (`MainInputNormalizationTest` precedent) plus the scripted-client seam for context injection

## Blocked by

- 03 (`03-shellhook-config-loading.md`) — shell tenant is the only consumer
