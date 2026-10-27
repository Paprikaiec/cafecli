# Claude Code agent context variants in the local source snapshot

This note describes the archived `claude-code/` tree in this repository, not a claim about current hosted product behavior. “Context” has two meanings: model input (`systemPrompt`, `userContext`, `systemContext`, `messages`, tool schemas) and the host's `ToolUseContext` execution object. An agent can have a fresh model conversation while sharing host resources.

| Entry path | Starting conversation | System prompt | Persistence/return |
|---|---|---|---|
| Named AgentTool general-purpose/Explore/Plan/custom | Delegated prompt, no parent messages | Selected agent's own prompt | Child sidechain; final text returns to parent |
| Background named AgentTool | Same delegated prompt policy | Same own prompt policy | Separate cancellation/task record; completion notification |
| Resumed AgentTool | Child's saved transcript + new prompt | Rebuilt agent prompt, special treatment for fork | Continues same child transcript |
| Gated implicit fork | Parent retained history + fork directive | Parent rendered prompt | Separate continuation/sidechain |
| Internal runForkedAgent | Caller-supplied forkContextMessages + instruction | Caller-supplied cacheSafeParams prompt | Returns messages to service; optional transcript |
| Skill context:fork | Prepared skill prompt, no automatic parent history | Base agent's prompt | Returns extracted text |
| In-process teammate | Fresh initial task, then its own history + inbox input | Full default + team/custom instructions | Persistent allMessages loop and mailbox |
| Separate-process teammate | Fresh CLI session + task mailbox | Its own process assembly | Separate session/process and mailbox |

The source-owning sections below support this comparison. The skill path is traced separately in [skills-and-context.md](/Users/wyw/Project/cafecli/.scratch/claude-code-memory/skills-and-context.md).

## Ordinary Agent tool: a fresh delegated conversation

The crucial call site is [AgentTool.tsx:604](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/AgentTool.tsx:604): only the special implicit fork path passes `toolUseContext.messages` as `forkContextMessages`. Ordinary explicitly selected agents, including general-purpose/Explore/Plan/custom definitions, pass `undefined`. [runAgent.ts:370](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:370) constructs `initialMessages = [...contextMessages, ...promptMessages]`, where contextMessages is otherwise empty. Thus the ordinary child does not automatically see the user's earlier conversation, parent reasoning, or earlier Read outputs. The parent must put necessary details into its delegated prompt.

Model input for an ordinary subagent is approximately:

```text
system = selected agent's own system prompt + environment details + systemContext
tools = independently assembled worker pool, filtered for this agent
user prefix = resolvedUserContext (same getUserContext loader, optional omissions)
messages = delegated user prompt + SubagentStart context + preloaded skills
           + this child's own assistant/tool exchanges
```

The selected agent's own prompt is enhanced with environment details at [AgentTool.tsx:520](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/AgentTool.tsx:520), or rebuilt under an overridden cwd at [runAgent.ts:906](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:906). It is not inherently the parent's full main-agent system prompt. Child query receives separate user/system context parameters at [runAgent.ts:748](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:748).

| Agent | Own system instructions | User/system context differences | Tools/model |
|---|---|---|---|
| general-purpose | Delegated task completion and concise final report | Standard getUserContext/getSystemContext | Wildcard tool request, still subject to subagent filters; model defaults to inherit |
| Explore | File search specialist, read-only | `omitClaudeMd: true`; gitStatus removed | No Agent/Edit/Write/NotebookEdit/ExitPlanMode; external users default Haiku |
| Plan | Architecture and planning, read-only | `omitClaudeMd: true`; gitStatus removed | Similar read-only exclusions; model inherit |
| Custom definition | Agent Markdown body or JSON system prompt | Standard contexts unless explicitly overridden elsewhere | Frontmatter tools/disallowedTools/model/permissionMode/etc. |

Definitions: [generalPurposeAgent.ts:28](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/built-in/generalPurposeAgent.ts:28), [exploreAgent.ts:76](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/built-in/exploreAgent.ts:76), [planAgent.ts:83](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/built-in/planAgent.ts:83), [loadAgentsDir.ts:714](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/loadAgentsDir.ts:714).

CLAUDE.md omission is conditional: agent definition's omitClaudeMd, no explicit userContext override, and `tengu_slim_subagent_claudemd` (default true). It removes the `claudeMd` key from the resolved prefix; it does not prohibit reading the file later. Explore/Plan unconditionally drop the session-start `gitStatus` context. [runAgent.ts:380](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:380).

The worker's starting pool is assembled independently of parent tool restrictions at [AgentTool.tsx:576](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/AgentTool.tsx:576). `resolveAgentTools` applies general subagent exclusions, custom/async exclusions, disallowedTools and explicit tools; MCP tools pass the first generic filter but are still subject to the subsequent explicit agent filter. [agentToolUtils.ts:70](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/agentToolUtils.ts:70), [agentToolUtils.ts:122](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/agentToolUtils.ts:122). Model resolution can differ across agents; `CLAUDE_CODE_SUBAGENT_MODEL`, tool argument, agent definition and inherited default participate. [agent.ts:25](/Users/wyw/Project/cafecli/claude-code/src/utils/model/agent.ts:25).

## Persistent agent memory and preloaded skills

An agent definition with `memory: user|project|local` gets a distinct agent-type directory, not an isolated per-invocation memory. Paths are respectively `<memoryBase>/agent-memory/<agentType>/`, `<cwd>/.claude/agent-memory/<agentType>/`, and `.claude/agent-memory-local/<agentType>/` (with worktree accommodation). [agentMemory.ts:25](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/agentMemory.ts:25).

Unlike main-thread auto-memory's ordinary user-context loading, this agent-specific memory prompt, including its MEMORY.md index content, is appended directly to the custom agent's **system prompt** by its getSystemPrompt callback. This requires auto memory enabled as well as the memory frontmatter. [loadAgentsDir.ts:726](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/loadAgentsDir.ts:726), [agentMemory.ts:138](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/agentMemory.ts:138), [memdir.ts:272](/Users/wyw/Project/cafecli/claude-code/src/memdir/memdir.ts:272).

Agent frontmatter `skills` loads each named skill's full prompt and appends it to initialMessages as an `isMeta: true` user message, independent of the user-context prefix. [runAgent.ts:577](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:577). A skill with `context: fork` is a separate use of runAgent; the word fork there does not imply inheritance of parent history (root investigation traces SkillTool.ts).

SubagentStart hooks can also contribute model input: their additionalContexts are appended as a `hook_additional_context` attachment before the child starts. [runAgent.ts:526](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:526).

## Foreground/background/resume

Foreground versus background changes scheduling, cancellation and host sharing; it does not by itself change whether parent history is inherited. Ordinary foreground and ordinary background subagents both start with their delegated prompt. Background can be requested by the tool/agent definition or forced by other feature modes. [AgentTool.tsx:553](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/AgentTool.tsx:553).

Synchronous agents normally share the parent's abort controller. Asynchronous agents receive a fresh unlinked controller, so cancellation of the parent's foreground turn need not cancel them. Explicit overrides take priority. [runAgent.ts:518](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:518).

Child exchanges are recorded to a sidechain transcript, under `<session project dir>/<session id>/subagents/agent-<id>.jsonl`, with agent type/worktree/description metadata. [runAgent.ts:732](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:732), [sessionStorage.ts:247](/Users/wyw/Project/cafecli/claude-code/src/utils/sessionStorage.ts:247).

Resume retrieves the child's own transcript, removes incomplete/invalid exchanges, reconstructs content replacement decisions, and adds the new delegated user prompt. It does **not** add the parent's newly accumulated messages. A resumed fork already has its original inherited slice in its transcript, so passing parent context again would duplicate tool IDs. [resumeAgent.ts:69](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/resumeAgent.ts:69), [resumeAgent.ts:170](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/resumeAgent.ts:170).

The parent ordinarily receives the child's final text, with a fallback to the most recent text response if the last assistant message is pure tool_use. Its entire internal tool transcript is not automatically appended to the parent model conversation. Foreground wraps this in an Agent tool_result; background first returns launch metadata and later enqueues a completion notification. [agentToolUtils.ts:276](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/agentToolUtils.ts:276), [AgentTool.tsx:1298](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/AgentTool.tsx:1298), [agentToolUtils.ts:597](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/agentToolUtils.ts:597).

## Special implicit fork experiment

When FORK_SUBAGENT is compiled in, the session is interactive and not coordinator mode, omitting subagent_type takes the implicit fork path; without the gate the default is ordinary general-purpose. Explicit subagent_type continues to choose ordinary behavior. [forkSubagent.ts:32](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/forkSubagent.ts:32), [AgentTool.tsx:318](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/AgentTool.tsx:318).

This path copies the parent's retained messages, exact already rendered system prompt, exact tool array, thinking config and noninteractive option for prompt cache prefix compatibility. It then adds a cloned current assistant/tool-call batch, placeholder results for every outstanding tool call, and the worker-specific directive. [AgentTool.tsx:483](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/AgentTool.tsx:483), [AgentTool.tsx:604](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/AgentTool.tsx:604), [forkSubagent.ts:130](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/forkSubagent.ts:130), [runAgent.ts:677](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:677).

The prompt is literally forked at spawn, not a live shared conversation. Subsequent parent/child messages diverge. This is a feature-gated alternate path; it cannot be generalized to every Agent invocation.

## Internal runForkedAgent helpers

The internal helper used by memory extraction and other services accepts CacheSafeParams containing systemPrompt/userContext/systemContext/toolUseContext/forkContextMessages. It starts a child query over `[...forkContextMessages, ...promptMessages]`, preserving the passed model input prefix while isolating execution state using createSubagentContext. It returns output messages to its caller; transcript persistence is optional with skipTranscript. [forkedAgent.ts:489](/Users/wyw/Project/cafecli/claude-code/src/utils/forkedAgent.ts:489).

This is a distinct path from normal AgentTool delegation and from a skill's context:fork.

## Persistent team agents

In-process teammates start without the leader's messages. The spawn code explicitly replaces `context.messages` with [] so a long-lived teammate cannot pin the parent history in memory. [spawnMultiAgent.ts:927](/Users/wyw/Project/cafecli/claude-code/src/tools/shared/spawnMultiAgent.ts:927).

Their default system prompt is the full Claude Code prompt plus teammate addendum, optional custom-agent instructions (including its own memory callback), and optional supplied append text. Explicit replace mode can replace that system prompt. [inProcessRunner.ts:923](/Users/wyw/Project/cafecli/claude-code/src/utils/swarm/inProcessRunner.ts:923).

They keep an `allMessages` array in the persistent runner closure. First iteration is their leader-supplied prompt wrapped in `<teammate-message>`. Later iterations pass the teammate's own accumulated messages to runAgent as forkContextMessages, then append the next inbox/user message. They compact their own accumulated conversation when necessary. [inProcessRunner.ts:1003](/Users/wyw/Project/cafecli/claude-code/src/utils/swarm/inProcessRunner.ts:1003), [inProcessRunner.ts:1065](/Users/wyw/Project/cafecli/claude-code/src/utils/swarm/inProcessRunner.ts:1065), [inProcessRunner.ts:1128](/Users/wyw/Project/cafecli/claude-code/src/utils/swarm/inProcessRunner.ts:1128).

Teammate output is not automatically returned wholesale to the leader after every turn. They communicate via messaging/mailboxes and send idle notifications; later input wakes the same runner with its own history. [inProcessRunner.ts:1328](/Users/wyw/Project/cafecli/claude-code/src/utils/swarm/inProcessRunner.ts:1328), [inProcessRunner.ts:1384](/Users/wyw/Project/cafecli/claude-code/src/utils/swarm/inProcessRunner.ts:1384).

Process-based teammates launch a separate Claude CLI with identity/model/permission flags and inherited environment. Initial task instructions travel through the mailbox, not serialized leader conversation. They have a separate process's session/config/client state. [spawnMultiAgent.ts:399](/Users/wyw/Project/cafecli/claude-code/src/tools/shared/spawnMultiAgent.ts:399), [spawnMultiAgent.ts:513](/Users/wyw/Project/cafecli/claude-code/src/tools/shared/spawnMultiAgent.ts:513).

## Runtime isolation is selective

createSubagentContext makes fresh nested-memory/skill discovery trigger sets, clones readFileState and contentReplacementState by default, provides a distinct agentId and cancellation controller, and disables most parent UI mutation callbacks. It does not deep-copy an entire session. getAppState still reads parent/root state; sync runAgent opts into shared setAppState, and root task mutations remain reachable through setAppStateForTasks even for asynchronous children. [forkedAgent.ts:345](/Users/wyw/Project/cafecli/claude-code/src/utils/forkedAgent.ts:345), [runAgent.ts:699](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:699).

MCP connections are runtime objects in options.mcpClients, not model messages. Ordinary child options reuse parent clients; agent-defined servers are additive. Named references may reuse memoized clients; inline clients are tracked for cleanup, but connection memoization means an inline definition does not guarantee a unique transport. Only tools/schemas, server instructions where used, and returned content become model input. [runAgent.ts:94](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:94), [runAgent.ts:159](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:159), [runAgent.ts:648](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:648), [client.ts:581](/Users/wyw/Project/cafecli/claude-code/src/services/mcp/client.ts:581).

Agent frontmatter hooks register in the root session store under this agent ID and are cleared in runAgent finally; this is separate from hook output that enters messages. Agent-specific MCP cleanup and cloned read-cache cleanup also occur in finally. [runAgent.ts:553](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:553), [runAgent.ts:817](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:817).

Identity/analytics context uses AsyncLocalStorage to isolate concurrent asynchronous execution chains. It carries agentId/type/name/team/lineage, not the conversation, MCP client pool, or full ToolUseContext. [agentContext.ts:1](/Users/wyw/Project/cafecli/claude-code/src/utils/agentContext.ts:1), [agentContext.ts:109](/Users/wyw/Project/cafecli/claude-code/src/utils/agentContext.ts:109).

## Appendix: why fork children receive placeholder results

The placeholder results belong to the **current assistant tool-call batch**, which is being dispatched when AgentTool executes. They do not replace every historical Read/Bash/Agent result in the retained conversation. Ordinary completed historical exchanges remain in the copied prefix; runAgent filters assistant messages with incomplete tool calls from that prefix before adding the specially constructed current batch. [runAgent.ts:370](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:370), [runAgent.ts:866](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/runAgent.ts:866).

For example, if the parent issues two fork calls in one assistant message, both children are constructed as follows:

```text
Child A                                       Child B
------------------------------------------    ------------------------------------------
same parent history, real old tool results     same parent history, real old tool results
assistant: Agent(A), Agent(B)                  assistant: Agent(A), Agent(B)
user:                                         user:
  tool_result(A, "Fork started ...")             tool_result(A, "Fork started ...")
  tool_result(B, "Fork started ...")             tool_result(B, "Fork started ...")
  text: worker rules + directive A               text: worker rules + directive B
```

The last user message contains placeholder tool_result blocks and a text instruction. It does **not** contain actual child results moved from elsewhere. The assistant tool_use and matching user tool_result blocks are already adjacent in the normal API structure. Keeping tool_result blocks in a separate user message is also the API representation used elsewhere, rather than interleaving them inside an assistant message. [forkSubagent.ts:141](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/forkSubagent.ts:141), [messages.ts:5270](/Users/wyw/Project/cafecli/claude-code/src/utils/messages.ts:5270).

There are three related reasons for this construction:

1. **Preserve the batch while completing tool pairing.** The cloned assistant message keeps every tool_use, text and thinking block. Creating a corresponding tool_result for every tool_use closes that batch for the child's next API request. The source's pairing repair separately checks the following message for results. [forkSubagent.ts:111](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/forkSubagent.ts:111), [messages.ts:5270](/Users/wyw/Project/cafecli/claude-code/src/utils/messages.ts:5270).
2. **The current batch is not supplied as settled results.** The fork input is built within AgentTool.call, before runAgent is launched and before its async_launched result is returned. In this gated asynchronous path, the genuine immediate Agent tool_result in the parent is launch metadata (including the particular agent ID), not the worker's final report. Other calls in the same batch can still be pending. Fixed placeholders let the child start with a complete, consistent batch without waiting for or collecting those results. This timing explanation is an inference from the call order. Separately, actual task output cannot exist until the child performs the task; that is different from the immediate launch result. [AgentTool.tsx:512](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/AgentTool.tsx:512), [AgentTool.tsx:733](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/AgentTool.tsx:733), [AgentTool.tsx:757](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/AgentTool.tsx:757), [AgentTool.tsx:1327](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/AgentTool.tsx:1327).
3. **Keep sibling prefixes identical until the directive.** Every child gets the complete same assistant batch and the same placeholder text for each tool ID. The final text block carries the worker's specific instruction. Using different or progressively arriving real results there would change this shared prefix. This cache-sharing intent is explicitly documented; the construction alone does not establish a measured cache hit or the exact token extent actually cached by the API. [forkSubagent.ts:91](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/forkSubagent.ts:91).

The code could conceptually encode the worker directive inside a tool_result instead: its own TODO mentions `smooshIntoToolResult` for that alternative. The current implementation chooses a text sibling following the placeholder blocks. Thus “a separate final text block” is an implementation choice for directing the worker, not a requirement that genuine results be relocated. [forkSubagent.ts:153](/Users/wyw/Project/cafecli/claude-code/src/tools/AgentTool/forkSubagent.ts:153).
